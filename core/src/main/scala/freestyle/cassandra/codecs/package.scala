/*
 * Copyright 2017 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package freestyle.cassandra

import java.nio.ByteBuffer

import com.datastax.driver.core.DataType
import com.datastax.driver.core.exceptions.InvalidTypeException
import com.datastax.driver.core.utils.Bytes

package object codecs {

  trait ByteBufferCodec[T] {
    def deserialize(bytes: ByteBuffer): T
    def serialize(value: T): ByteBuffer
  }

  abstract class PrimitiveByteBufferCodec[T](dataType: DataType, byteSize: Int, defaultValue: T)
      extends ByteBufferCodec[T] {

    def deserialize(bytes: ByteBuffer): T =
      Option(bytes) match {
        case None                                 => defaultValue
        case Some(b) if b.remaining() == 0        => defaultValue
        case Some(b) if b.remaining() == byteSize => getValue(b)
        case Some(b) =>
          throw new InvalidTypeException(
            s"Invalid value, expecting $byteSize but got ${b.remaining}")
      }

    def serialize(value: T): ByteBuffer =
      setValue(ByteBuffer.allocate(byteSize), value)

    protected def getValue(byteBuffer: ByteBuffer): T

    private[this] def setValue(byteBuffer: ByteBuffer, value: T): ByteBuffer = value match {
      case v: Boolean if v => ByteBuffer.wrap(Array[Byte](1))
      case _: Boolean      => ByteBuffer.wrap(Array[Byte](0))
      case v: Byte         => byteBuffer.put(0, v)
      case v: Double       => byteBuffer.putDouble(0, v)
      case v: Float        => byteBuffer.putFloat(0, v)
      case v: Int          => byteBuffer.putInt(0, v)
      case v: Long         => byteBuffer.putLong(0, v)
      case v: Short        => byteBuffer.putShort(0, v)
    }
  }

  object PrimitiveByteBufferCodec {
    def apply[T](dataType: DataType, byteSize: Int, defaultValue: T)(
        f: (ByteBuffer) => T): PrimitiveByteBufferCodec[T] =
      new PrimitiveByteBufferCodec(dataType, byteSize, defaultValue) {
        override protected def getValue(byteBuffer: ByteBuffer): T = f(byteBuffer)
      }
  }

  implicit val booleanCodec: ByteBufferCodec[Boolean] =
    PrimitiveByteBufferCodec[Boolean](DataType.smallint(), byteSize = 1, defaultValue = false) {
      byteBuffer =>
        byteBuffer.get(byteBuffer.position()) == 1
    }

  implicit val byteCodec: ByteBufferCodec[Byte] =
    PrimitiveByteBufferCodec[Byte](DataType.smallint(), byteSize = 1, defaultValue = 0) {
      byteBuffer =>
        byteBuffer.get(byteBuffer.position())
    }

  implicit val doubleCodec: ByteBufferCodec[Double] =
    PrimitiveByteBufferCodec[Double](DataType.cdouble(), byteSize = 8, defaultValue = 0) {
      byteBuffer =>
        byteBuffer.getDouble(byteBuffer.position())
    }

  implicit val floatCodec: ByteBufferCodec[Float] =
    PrimitiveByteBufferCodec[Float](DataType.cfloat(), byteSize = 4, defaultValue = 0) {
      byteBuffer =>
        byteBuffer.getFloat(byteBuffer.position())
    }

  implicit val intCodec: ByteBufferCodec[Int] =
    PrimitiveByteBufferCodec[Int](DataType.cint, byteSize = 4, defaultValue = 0) { byteBuffer =>
      byteBuffer.getInt(byteBuffer.position())
    }

  implicit val longCodec: ByteBufferCodec[Long] =
    PrimitiveByteBufferCodec[Long](DataType.bigint(), byteSize = 8, defaultValue = 0) {
      byteBuffer =>
        byteBuffer.getLong(byteBuffer.position())
    }

  implicit val shortCodec: ByteBufferCodec[Short] =
    PrimitiveByteBufferCodec[Short](DataType.smallint(), byteSize = 2, defaultValue = 0) {
      byteBuffer =>
        byteBuffer.getShort(byteBuffer.position())
    }

  implicit val stringCodec: ByteBufferCodec[String] = new ByteBufferCodec[String] {

    import java.nio.charset.StandardCharsets._

    override def deserialize(bytes: ByteBuffer): String =
      Option(bytes) match {
        case None                          => ""
        case Some(b) if b.remaining() == 0 => ""
        case Some(b)                       => new String(Bytes.getArray(b), UTF_8)
      }

    override def serialize(value: String): ByteBuffer = ByteBuffer.wrap(value.getBytes(UTF_8))
  }

}