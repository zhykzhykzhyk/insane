package insane
package predefined

import annotations._

@AbstractsClass("java.lang.NullPointerException")
class javalangNullPointerException {
  @AbstractsMethod("java.lang.NullPointerException.<init>(()java.lang.NullPointerException)")
  def __init__(): java.lang.NullPointerException = {
    new java.lang.NullPointerException()
  }
}
