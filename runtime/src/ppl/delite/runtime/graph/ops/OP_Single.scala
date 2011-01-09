package ppl.delite.runtime.graph.ops

import ppl.delite.runtime.graph.targets._

/**
 * Author: Kevin J. Brown
 * Date: Nov 14, 2010
 * Time: 10:12:48 PM
 * 
 * Pervasive Parallelism Laboratory (PPL)
 * Stanford University
 */

class OP_Single(val id: String, kernel: String, resultType: Map[Targets.Value, String]) extends DeliteOP {

  final def isDataParallel = false

  def task = kernel

  def supportsTarget(target: Targets.Value) = resultType.contains(target)

  def outputType(target: Targets.Value) = resultType(target)
  override def outputType: String = resultType(Targets.Scala)

  def nested = null
  def cost = 0
  def size = 0

  def duplicate(id: String): OP_Single = {
    val dup = new OP_Single(id, kernel, resultType)
    dup.dependencyList = dependencyList
    dup.consumerList = consumerList
    dup.inputList = inputList
    dup.cudaMetadata = cudaMetadata
    dup
  }

}
