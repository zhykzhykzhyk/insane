package insane
package predefined

import annotations._

@AbstractsClass("java.io.InputStream")
class javaioInputStream {
  @AbstractsMethod("java.io.InputStream.close(()Unit)")
  def __close(): Unit = {
    ()
  }
  @AbstractsMethod("java.io.InputStream.mark((x$1: Int)Unit)")
  def __mark(x1: Int): Unit = {
    ()
  }
  @AbstractsMethod("java.io.InputStream.read(()Int)")
  def __read(): Int = {
    42
  }
  @AbstractsMethod("java.io.InputStream.read((x$1: Array[Byte])Int)")
  def __read(x1: Array[Byte]): Int = {
    42
  }
  @AbstractsMethod("java.io.InputStream.read((x$1: Array[Byte], x$2: Int, x$3: Int)Int)")
  def __read(x1: Array[Byte], x2: Int, x3: Int): Int = {
    42
  }
  @AbstractsMethod("java.io.InputStream.reset(()Unit)")
  def __reset(): Unit = {
    ()
  }
}
