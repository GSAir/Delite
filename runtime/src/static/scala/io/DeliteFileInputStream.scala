package generated.scala.io

import com.google.protobuf.ByteString
import java.io.{InputStream, IOException, DataInput, DataOutput}
import java.nio.charset.Charset

import org.apache.hadoop.conf._
import org.apache.hadoop.fs._
import org.apache.hadoop.io.Text
import org.apache.hadoop.util.LineReader

/**
 * Constructs a logical file stream by concatenating the streams of an arbitrary number of physical files.
 * The stream can be opened at any byte offset and it will automatically seek to the next full line.
 * Each path in the stream can be backed by a different Hadoop-supported filesystem (inclues local, HDFS and S3).
 */

object DeliteFileInputStream {

  /* Construct a new DeliteFileInputStream */
  def apply(paths: Seq[String], charsetName: Option[String] = None, delimiter: Option[Array[Byte]] = None, offset: Long = 0L): DeliteFileInputStream = {
    val charset = checkCharset(charsetName)
    val conf = new Configuration()
    // We pre-load the file handles so that we can easily copy the stream wrapper instance at run-time
    val fileHandles = getFiles(conf, paths)
    new DeliteFileInputStream(conf, fileHandles, charset, delimiter, offset)
  }

  /* Each path must refer to a valid filesystem, based on the Hadoop configuration object. */
  private def getFiles(conf: Configuration, paths:Seq[String]) = paths.toArray flatMap { p =>
    val hPath = new Path(p)
    val fs = hPath.getFileSystem(conf)

    // recurse into sub-directories
    def listStatus(p: Path): Array[FileStatus] = {
      fs.listStatus(p) flatMap { s =>
        if (s.isDirectory) listStatus(s.getPath)
        else Array(s)
      }
    }

    if (fs.isDirectory(hPath)) {
      // return sorted list of file statuses (numbered file chunks should be parsed in order)
      listStatus(hPath).sortBy(f => f.getPath.getName)
    }
    else if (fs.isFile(hPath)) Array(fs.getFileStatus(hPath))
    else throw new IOException("Path " + p + " does not appear to be a valid file or directory")
  }

  /* Validate that the specified charset name is legal and supported */
  private def checkCharset(charsetName: Option[String]) = {
    val charset = charsetName map { Charset.forName } getOrElse Charset.defaultCharset
    
    //we only support backwards-compatible charsets (all extended ascii and utf-8); this is a hacky way of checking that
    val dec = charset.newDecoder
    if (dec.maxCharsPerByte != 1f || dec.averageCharsPerByte != 1f)
      throw new IOException("Unsupported Charset: " + charset.displayName)
    charset
  }

  /* Deserialization for a DeliteFileInputStream, using Hadoop serialization for Hadoop objects */
  def deserialize(bytes: ByteString): DeliteFileInputStream = {
    val in = new java.io.DataInputStream(bytes.newInput)
    val conf = new Configuration()
    conf.readFields(in)
    val filesLen = in.readInt()
    val files = Array.fill(filesLen) {
      val fs = new FileStatus()
      fs.readFields(in)
      fs
    }
    val charset = Charset.defaultCharset
    val delimiterExists = in.readBoolean()
    val delimiter =
      if (delimiterExists) {
        val delimiterLen = in.readInt()
        Some(Array.fill(delimiterLen) { in.readByte() })
      }
      else None

    val streamOffset = in.readLong()
    val dfis = new DeliteFileInputStream(conf, files, charset, delimiter, streamOffset)
    val pos = in.readLong()
    dfis.openAtNewLine(pos)
    dfis
  }

}

class DeliteFileInputStream(conf: Configuration, files: Array[FileStatus], charset: Charset, delimiter: Option[Array[Byte]], val streamOffset: Long) {
  private[this] var reader: LineReader = _
  private[this] var text: Text = _
  private[this] var pos: Long = _
  private[this] var filePos: Int = _

  final val size: Long = files.map(_.getLen).sum
  final def position = pos

  /* Initialize. This is only required / used when opening a stream directly (i.e. not via multiloop) */
  if (size > 0) {
    open()
  }
  else {
    throw new IOException("DeliteFileInputStream opened with size == 0. Paths were: " + files.map(_.getPath.getName).mkString("[",",","]"))
  }

  /* Determine the file that this logical index corresponds to, as well as the byte offset within the file. */
  private def findFileOffset(start: Long) = {
    var offset = start
    var fileIdx = 0
    while (offset >= files(fileIdx).getLen) {
      offset -= files(fileIdx).getLen
      fileIdx += 1
    }
    (fileIdx, offset)
  }

  /*
   * Return an input stream opened at the next full line after 'offset'
   * Offset refers to the number of bytes inside the physical resource at 'fileIdx'
   */
  private def openInputStream(fileIdx: Int, offset: Long) = {
    if (fileIdx >= files.length) throw new IndexOutOfBoundsException("Cannot open file at index: " + fileIdx + ", num files is: " + files.length)
    val path = files(fileIdx).getPath
    val byteStream = path.getFileSystem(conf).open(path)
    val reader = new LineReader(byteStream, delimiter.getOrElse(null))

    if (offset != 0) { 
      if (byteStream.skip(offset-1) != (offset-1)) //seek to offset; -1 ensures we won't skip a full line on first readLine() 
        throw new IOException("Unable to skip desired bytes in file")
      pos += (reader.readLine(text) - 1) //jump to next available new line (and valid char)
    }
    reader
  }

  /* Set the line reader to a newline-aligned input stream corresponding to logical byte index 'startPosition' */
  final def openAtNewLine(startPosition: Long) {
    close()
    val start = streamOffset + startPosition
    if (start >= size) throw new IndexOutOfBoundsException("Cannot load stream at pos " + start + ", stream size is: " + size)
    pos = start
    
    val (fileIdx, offset) = findFileOffset(start)
    filePos = fileIdx
    text = new Text
    reader = openInputStream(fileIdx, offset)
  }

  /* Construct a copy of this DeliteFileInputStream, starting at logical byte index 'start' */
  final def openCopyAtNewLine(start: Long): DeliteFileInputStream = {
    val copy = new DeliteFileInputStream(conf, files, charset, delimiter, streamOffset)
    copy.openAtNewLine(start)
    copy
  }

  final def open() {
    openAtNewLine(0)
  }

  /* Read the next line */
  private def readLineInternal() {
    var length = reader.readLine(text)
    if (length == 0) {
      reader.close()
      if (pos >= size) {
        text = null
        return
      }
      else {
        filePos += 1
        reader = openInputStream(filePos, 0)
        length = reader.readLine(text)
        assert(length != 0, "FileSystem returned an invalid input stream for position " + pos)
      }
    }
    pos += length
  }

  /* Read the next line and interpret it as a String */
  final def readLine(): String = {
    readLineInternal()
    if (text eq null) null
    else new String(text.getBytes, 0, text.getLength, charset)
  }

  /* Read the next line and interpret it as bytes */
  final def readBytes(): Array[Byte] = {
    readLineInternal()
    if (text eq null) null
    else {
      val record = new Array[Byte](text.getLength)
      System.arraycopy(text.getBytes, 0, record, 0, text.getLength)
      record
    }
  }

  /* Close the DeliteFileInputStream */
  final def close(): Unit = {
    if (reader != null) {
      reader.close()
      reader = null
    }
    text = null
    pos = 0
    filePos = 0
  }



  /////////////////////////////////////////////////////////////////////////////
  // Serialization
  /////////////////////////////////////////////////////////////////////////////

  /**
   * Serialize the DeliteFileInputStream using Hadoop serialization for Hadoop objects.
   */
  def serialize: ByteString = {
    val outBytes = ByteString.newOutput
    val out = new java.io.DataOutputStream(outBytes)
    conf.write(out)
    out.writeInt(files.length)
    files.foreach(f => f.write(out))
    if (charset != Charset.defaultCharset) throw new UnsupportedOperationException("Don't know how to serialize custom charset " + charset)
    if (delimiter.isDefined) {
      out.writeBoolean(true)
      out.writeInt(delimiter.get.length)
      delimiter.get.foreach { b => out.writeByte(b) }
    }
    else {
      out.writeBoolean(false)
    }
    out.writeLong(streamOffset)
    out.writeLong(pos)
    outBytes.toByteString
  }

  /* Deserialization is static (see DeliteFileInputStream.deserialize) */

}
