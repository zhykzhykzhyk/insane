package insane
package analysis

import AST.Extractors

import utils.SubPhase

trait CodeExtraction extends Extractors with Contracts {
  self: AnalysisComponent =>

  import global._

  import StructuralExtractors._

  class CodeExtractionPhase extends SubPhase {
    val name = "Extracting definitions and contracts"

    def run() {
      for (unit <- currentRun.units) {
        new ForeachTreeTraverser(traverseStep).traverse(unit.body)
      }
    }

    def traverseStep(tree: Tree) {
      tree match {
        case d @ DefDef(_, name, _, argsargs, _, rhs) =>
          assert(argsargs.size == 1) // We are late enough as a phase

          val (requs, enss, asss) = extractFunBody(rhs)
          val f = new NamedFunction(d.symbol, name, argsargs.head, rhs)

          f.contrRequires = requs
          f.contrEnsures  = enss
          f.contrAsserts  = asss

          funDecls += f.symbol -> f
        case d @ Function(args, rhs) =>
          val (requs, enss, asss) = extractFunBody(rhs)
          val f = new AnnonFunction(d.symbol, args, rhs)

          f.contrRequires = requs
          f.contrEnsures  = enss
          f.contrAsserts  = asss

          funDecls += f.symbol -> f
        case _ =>
      }
    }


    def extractFunBody(body: Tree): (Seq[Requires], Seq[Ensures], Seq[Assert]) = {
      var realBody = body
      var requs    = Seq[Requires]()
      var enss     = Seq[Ensures]()
      var asserts  = Seq[Assert]()

      realBody match {
        case ExEnsuredExpression(innerBody, resSym, contract) =>
          realBody = innerBody
          enss :+= new Ensures(contract)
        case _ =>
      }

      var continue = true
      while(continue) {
        realBody match {
          case ExRequiredExpression(innerBody, contract) =>
            realBody = innerBody
            requs :+= new Requires(contract)
          case _ =>
            continue = false
        }
      }

      def assertFind(tree: Tree) {
        tree match {
          case ExAssertExpression(contract) =>
            asserts :+= new Assert(tree, contract)
          case _ =>
        }
      }

      new ForeachTreeTraverser(assertFind).traverse(realBody)

      (requs, enss, asserts)
    }
  }
}
