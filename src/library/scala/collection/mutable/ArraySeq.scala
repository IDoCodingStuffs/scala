/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.collection
package mutable

import java.util.Arrays

import scala.collection.Stepper.EfficientSplit
import scala.reflect.ClassTag
import scala.runtime.ScalaRunTime
import scala.util.hashing.MurmurHash3

/**
  *  A collection representing `Array[T]`. Unlike `ArrayBuffer` it is always backed by the same
  *  underlying `Array`, therefore it is not growable or shrinkable.
  *
  *  @tparam T    type of the elements in this wrapped array.
  *
  *  @since 2.8
  *  @define Coll `ArraySeq`
  *  @define coll wrapped array
  *  @define orderDependent
  *  @define orderDependentFold
  *  @define mayNotTerminateInf
  *  @define willNotTerminateInf
  */
@SerialVersionUID(3L)
sealed abstract class ArraySeq[T]
  extends AbstractSeq[T]
    with IndexedSeq[T]
    with IndexedSeqOps[T, ArraySeq, ArraySeq[T]]
    with StrictOptimizedSeqOps[T, ArraySeq, ArraySeq[T]]
    with Serializable {

  override def iterableFactory: scala.collection.SeqFactory[ArraySeq] = ArraySeq.untagged

  override protected def fromSpecific(coll: scala.collection.IterableOnce[T]): ArraySeq[T] = {
    val b = ArrayBuilder.make(elemTag).asInstanceOf[ArrayBuilder[T]]
    val s = coll.knownSize
    if(s > 0) b.sizeHint(s)
    b ++= coll
    ArraySeq.make(b.result())
  }
  override protected def newSpecificBuilder: Builder[T, ArraySeq[T]] = ArraySeq.newBuilder(elemTag).asInstanceOf[Builder[T, ArraySeq[T]]]
  override def empty: ArraySeq[T] = ArraySeq.empty(elemTag.asInstanceOf[ClassTag[T]])

  /** The tag of the element type. This does not have to be equal to the element type of this ArraySeq. A primitive
    * ArraySeq can be backed by an array of boxed values and a reference ArraySeq can be backed by an array of a supertype
    * or subtype of the element type. */
  def elemTag: ClassTag[_]

  /** Update element at given index */
  def update(@deprecatedName("idx", "2.13.0") index: Int, elem: T): Unit

  /** The underlying array. Its element type does not have to be equal to the element type of this ArraySeq. A primitive
    * ArraySeq can be backed by an array of boxed values and a reference ArraySeq can be backed by an array of a supertype
    * or subtype of the element type. */
  def array: Array[_]

  override def stepper[S <: Stepper[_]](implicit shape: StepperShape[T, S]): S with EfficientSplit = {
    import scala.collection.convert.impl._
    val isRefShape = shape.shape == StepperShape.ReferenceShape
    val s = if (isRefShape) array match {
      case a: Array[Int]     => AnyStepper.ofParIntStepper   (new IntArrayStepper(a, 0, a.length))
      case a: Array[Long]    => AnyStepper.ofParLongStepper  (new LongArrayStepper(a, 0, a.length))
      case a: Array[Double]  => AnyStepper.ofParDoubleStepper(new DoubleArrayStepper(a, 0, a.length))
      case a: Array[Byte]    => AnyStepper.ofParIntStepper   (new WidenedByteArrayStepper(a, 0, a.length))
      case a: Array[Short]   => AnyStepper.ofParIntStepper   (new WidenedShortArrayStepper(a, 0, a.length))
      case a: Array[Char]    => AnyStepper.ofParIntStepper   (new WidenedCharArrayStepper(a, 0, a.length))
      case a: Array[Float]   => AnyStepper.ofParDoubleStepper(new WidenedFloatArrayStepper(a, 0, a.length))
      case a: Array[Boolean] => new BoxedBooleanArrayStepper(a, 0, a.length)
      case a: Array[AnyRef]  => new ObjectArrayStepper(a, 0, a.length)
    } else {
      array match {
        case a: Array[AnyRef] => shape.parUnbox(new ObjectArrayStepper(a, 0, a.length).asInstanceOf[AnyStepper[T] with EfficientSplit])
        case a: Array[Int]    => new IntArrayStepper(a, 0, a.length)
        case a: Array[Long]   => new LongArrayStepper(a, 0, a.length)
        case a: Array[Double] => new DoubleArrayStepper(a, 0, a.length)
        case a: Array[Byte]   => new WidenedByteArrayStepper(a, 0, a.length)
        case a: Array[Short]  => new WidenedShortArrayStepper(a, 0, a.length)
        case a: Array[Char]   => new WidenedCharArrayStepper(a, 0, a.length)
        case a: Array[Float]  => new WidenedFloatArrayStepper(a, 0, a.length)
      }
    }
    s.asInstanceOf[S with EfficientSplit]
  }

  override protected[this] def stringPrefix = "ArraySeq"

  /** Clones this object, including the underlying Array. */
  override def clone(): ArraySeq[T] = ArraySeq.make(array.clone()).asInstanceOf[ArraySeq[T]]

  override def copyToArray[B >: T](xs: Array[B], start: Int): Int = copyToArray[B](xs, start, length)

  override def copyToArray[B >: T](xs: Array[B], start: Int, len: Int): Int = {
    val copied = IterableOnce.elemsToCopyToArray(length, xs.length, start, len)
    if(copied > 0) {
      Array.copy(array, 0, xs, start, copied)
    }
    copied
  }

  override def equals(other: Any): Boolean = other match {
    case that: ArraySeq[_] if this.array.length != that.array.length =>
      false
    case _ =>
      super.equals(other)
  }

  override def sorted[B >: T](implicit ord: Ordering[B]): ArraySeq[T] =
    ArraySeq.make(array.sorted(ord.asInstanceOf[Ordering[Any]])).asInstanceOf[ArraySeq[T]]

  override def sortInPlace[B >: T]()(implicit ord: Ordering[B]): this.type = {
    if (length > 1) scala.util.Sorting.stableSort(array.asInstanceOf[Array[B]])
    this
  }
}

/** A companion object used to create instances of `ArraySeq`.
  */
@SerialVersionUID(3L)
object ArraySeq extends StrictOptimizedClassTagSeqFactory[ArraySeq] { self =>
  val untagged: SeqFactory[ArraySeq] = new ClassTagSeqFactory.AnySeqDelegate(self)

  // This is reused for all calls to empty.
  private[this] val EmptyArraySeq  = new ofRef[AnyRef](new Array[AnyRef](0))
  def empty[T : ClassTag]: ArraySeq[T] = EmptyArraySeq.asInstanceOf[ArraySeq[T]]

  def from[A : ClassTag](it: scala.collection.IterableOnce[A]): ArraySeq[A] = {
    val n = it.knownSize
    if (n > -1) {
      val elements = scala.Array.ofDim[A](n)
      val iterator = it.iterator
      var i = 0
      while (i < n) {
        ScalaRunTime.array_update(elements, i, iterator.next())
        i = i + 1
      }
      make(elements)
    } else make(ArrayBuffer.from(it).toArray)
  }

  def newBuilder[A : ClassTag]: Builder[A, ArraySeq[A]] = ArrayBuilder.make[A].mapResult(make)

  /**
   * Wrap an existing `Array` into a `ArraySeq` of the proper primitive specialization type
   * without copying.
   *
   * Note that an array containing boxed primitives can be converted to a `ArraySeq` without
   * copying. For example, `val a: Array[Any] = Array(1)` is an array of `Object` at runtime,
   * containing `Integer`s. An `ArraySeq[Int]` can be obtained with a cast:
   * `ArraySeq.make(a).asInstanceOf[ArraySeq[Int]]`. The values are still
   * boxed, the resulting instance is an [[ArraySeq.ofRef]]. Writing
   * `ArraySeq.make(a.asInstanceOf[Array[Int]])` does not work, it throws a `ClassCastException`
   * at runtime.
   */
  def make[T](x: Array[T]): ArraySeq[T] = (x.asInstanceOf[Array[_]] match {
    case null              => null
    case x: Array[AnyRef]  => new ofRef[AnyRef](x)
    case x: Array[Int]     => new ofInt(x)
    case x: Array[Double]  => new ofDouble(x)
    case x: Array[Long]    => new ofLong(x)
    case x: Array[Float]   => new ofFloat(x)
    case x: Array[Char]    => new ofChar(x)
    case x: Array[Byte]    => new ofByte(x)
    case x: Array[Short]   => new ofShort(x)
    case x: Array[Boolean] => new ofBoolean(x)
    case x: Array[Unit]    => new ofUnit(x)
  }).asInstanceOf[ArraySeq[T]]

  @SerialVersionUID(3L)
  final class ofRef[T <: AnyRef](val array: Array[T]) extends ArraySeq[T] {
    lazy val elemTag = ClassTag[T](array.getClass.getComponentType)
    def length: Int = array.length
    def apply(index: Int): T = array(index)
    def update(index: Int, elem: T): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofRef[_] =>
        Array.equals(
          this.array.asInstanceOf[Array[AnyRef]],
          that.array.asInstanceOf[Array[AnyRef]])
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofByte(val array: Array[Byte]) extends ArraySeq[Byte] {
    def elemTag = ClassTag.Byte
    def length: Int = array.length
    def apply(index: Int): Byte = array(index)
    def update(index: Int, elem: Byte): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofByte => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofShort(val array: Array[Short]) extends ArraySeq[Short] {
    def elemTag = ClassTag.Short
    def length: Int = array.length
    def apply(index: Int): Short = array(index)
    def update(index: Int, elem: Short): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofShort => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofChar(val array: Array[Char]) extends ArraySeq[Char] {
    def elemTag = ClassTag.Char
    def length: Int = array.length
    def apply(index: Int): Char = array(index)
    def update(index: Int, elem: Char): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofChar => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }

    override def addString(sb: StringBuilder, start: String, sep: String, end: String): StringBuilder = {
      val jsb = sb.underlying
      if (start.length != 0) jsb.append(start)
      val len = array.length
      if (len != 0) {
        if (sep.isEmpty) jsb.append(array)
        else {
          jsb.ensureCapacity(jsb.length + len + end.length + (len - 1) * sep.length)
          jsb.append(array(0))
          var i = 1
          while (i < len) {
            jsb.append(sep)
            jsb.append(array(i))
            i += i
          }
        }
      }
      if (end.length != 0) jsb.append(end)
      sb
    }
  }

  @SerialVersionUID(3L)
  final class ofInt(val array: Array[Int]) extends ArraySeq[Int] {
    def elemTag = ClassTag.Int
    def length: Int = array.length
    def apply(index: Int): Int = array(index)
    def update(index: Int, elem: Int): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofInt => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofLong(val array: Array[Long]) extends ArraySeq[Long] {
    def elemTag = ClassTag.Long
    def length: Int = array.length
    def apply(index: Int): Long = array(index)
    def update(index: Int, elem: Long): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofLong => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofFloat(val array: Array[Float]) extends ArraySeq[Float] {
    def elemTag = ClassTag.Float
    def length: Int = array.length
    def apply(index: Int): Float = array(index)
    def update(index: Int, elem: Float): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofFloat => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofDouble(val array: Array[Double]) extends ArraySeq[Double] {
    def elemTag = ClassTag.Double
    def length: Int = array.length
    def apply(index: Int): Double = array(index)
    def update(index: Int, elem: Double): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofDouble => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofBoolean(val array: Array[Boolean]) extends ArraySeq[Boolean] {
    def elemTag = ClassTag.Boolean
    def length: Int = array.length
    def apply(index: Int): Boolean = array(index)
    def update(index: Int, elem: Boolean): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofBoolean => Arrays.equals(array, that.array)
      case _ => super.equals(that)
    }
  }

  @SerialVersionUID(3L)
  final class ofUnit(val array: Array[Unit]) extends ArraySeq[Unit] {
    def elemTag = ClassTag.Unit
    def length: Int = array.length
    def apply(index: Int): Unit = array(index)
    def update(index: Int, elem: Unit): Unit = { array(index) = elem }
    override def hashCode = MurmurHash3.arraySeqHash(array)
    override def equals(that: Any) = that match {
      case that: ofUnit => array.length == that.array.length
      case _ => super.equals(that)
    }
  }
}
