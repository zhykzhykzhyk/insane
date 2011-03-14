package insane
package utils

import scala.tools.nsc._
trait Functions {
  self : Contracts =>
  val global: Global

  import global._
  import global.definitions._

  sealed abstract class AbsFunction {
    val symbol: Symbol;
    val body: Tree
    val argsargs: Seq[Seq[ValDef]]

    /* contracts */
    var contrRequires = Seq[Requires]()
    var contrEnsures  = Seq[Ensures]()
  }

  class NamedFunction(val symbol: Symbol, val name: Name, val argsargs: Seq[Seq[ValDef]], val body: Tree) extends AbsFunction {

  }

  class AnnonFunction(val symbol: Symbol, val args: Seq[ValDef], val body: Tree) extends AbsFunction {
    val argsargs = Seq(args)

  }
}