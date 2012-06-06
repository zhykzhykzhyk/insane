package insane
package alias

import utils.Graphs._
import utils._

import scala.tools.nsc.symtab.Flags

trait PointToGraphsDefs {
  self: AnalysisComponent =>

  import global._

  abstract class Field {
    val sym: Symbol
    val info: TypeInfo
    lazy val name: Name = sym.name
    def definingClass    = sym.owner
    def definingClassTpe = sym.owner.tpe


    def accessFromNode(n: PointToGraphs.Node): Option[SigEntry]

    def existsFromNode(n: PointToGraphs.Node) = !accessFromNode(n).isEmpty
  }

  case class NormalField(val sym: Symbol, val info: TypeInfo) extends Field {
    def accessFromNode(n: PointToGraphs.Node): Option[SigEntry] = {
      n.sig.preciseSigFor(this) match {
        case Some(fieldSig) =>
          // If we already have a precise signature for this field
          Some(fieldSig)
        case None =>
          // Otherwise, we look in the type declarations
          val tpe = n.types.tpe
          val s = tpe.decl(name)

          if (s == NoSymbol) {
            // Might be a private field in the parent class
            // or a parent class if imprecise
            if ((tpe <:< definingClassTpe) || (definingClassTpe <:< tpe)) {
              Some(SigEntry.fromTypeInfo(info))
            } else {
              None
            }
          } else {
            val realTpe  = tpe.memberType(s)
            val fieldSig = SigEntry.fromTypeInfo(TypeInfo.subtypeOf(realTpe))

            Some(fieldSig)
          }
      }
    }
  }

  // This is a special field generated by stubs' implementations to allow
  // type-incorrect fields to be propagated from implementations to stub objects
  case class GhostField(val sym: Symbol, val info: TypeInfo) extends Field {
    def accessFromNode(n: PointToGraphs.Node): Option[SigEntry] = {
      Some(SigEntry.fromTypeInfo(info))
    }
  }

  object NoField extends NormalField(NoSymbol, TypeInfo.empty)

  object Field {
    def apply(sym: Symbol): Field = {
      if (sym.annotations.exists(_.atp.safeToString == "insane.annotations.GhostField")) {
        GhostField(sym, TypeInfo.subtypeOf(sym.tpe))
      } else {
        NormalField(sym, TypeInfo.subtypeOf(sym.tpe))
      }
    }
  }


  object PointToGraphs {
    sealed abstract class Node(val name: String, val isSingleton: Boolean) extends VertexAbs {
      /**
       * Type represented by the node
       */
      val types: TypeInfo
      /**
       * Type signature of that node, might be more precise than types w.r.t. fields
       */
      val sig: SigEntry
      val isResolved: Boolean

      def withTypes(tpe: TypeInfo): Node
    }

    case class VNode(ref: CFG.Ref) extends Node(""+ref.toString+"", false) {
      val types = TypeInfo.empty
      val sig   = SigEntry.empty
      val isResolved = true

      def withTypes(tpe: TypeInfo) = sys.error("VNode.withTypes()")
    }

    trait GloballyReachableNode {
      // Globally reachable nodes are nodes that may be accessible from outside
      // Parameter nodes (LVNodes) are handled separately and are not of type
      // GloballyReachableNode per se.
    }

    trait SimpleNode {
      // Simple nodes get copied directly, they get inlined directly.
      // Singletons and literal nodes are simplenodes
      val isResolved = true
      def withTypes(tpe: TypeInfo) = sys.error(this+".withTypes()")
    }

    case class LVNode(ref: CFG.Ref, sig: SigEntry) extends Node("Loc("+ref+")", true) {
      val types = sig.info
      val isResolved = false

      def withTypes(tpe: TypeInfo) = LVNode(ref, sig.withInfo(tpe))
    }

    case class INode(pPoint: UniqueID, sgt: Boolean, sym: Symbol) extends Node(sym.name+"@"+pPoint, sgt) {
      val types = TypeInfo.exact(sym.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
      val isResolved = true

      def withTypes(tpe: TypeInfo) = sys.error("INode.withTypes()")
    }

    // mutable fromNode is only used when unserializing
    case class LNode(var fromNode: Node, via: Field, pPoint: UniqueID, sig: SigEntry) extends Node("L"+pPoint, true) {
      val types = sig.info
      val isResolved = false

      def withTypes(tpe: TypeInfo) = LNode(fromNode, via, pPoint, sig.withInfo(tpe))
    }

    case class OBNode(s: Symbol) extends Node("Obj("+s.name+")", true) with GloballyReachableNode {
      val types = TypeInfo.exact(s.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
      val isResolved = true
      def withTypes(tpe: TypeInfo) = sys.error(this+".withTypes()")
    }

    /*
    def findSimilarLNodes(lNode: LNode, others: Set[Node]): Set[LNode] = {
      // No need to have more than one lNode with exactly the same type
      var foundExactMatch = false
      val res = others.collect {
        case l: LNode if (l != lNode) &&
                         (l.fromNode, l.via, l.pPoint) == (lNode.fromNode, lNode.via, lNode.pPoint) &&
                         (l.types isMorePreciseThan lNode.types) =>

         if (l.types == lNode.types) {
          foundExactMatch = true
         }
         l
      }

      if (foundExactMatch) {
        res
      } else {
        Set(lNode) ++ res
      }
    }
    */

    def safeLNode(from: Node, via: Field, pPoint: UniqueID): Option[LNode] = {
      via.accessFromNode(from) match {
        case Some(sig) =>
          Some(safeTypedLNode(sig, from, via, pPoint))
        case None =>
          None
      }
    }


    def safeTypedLNode(sig: SigEntry, from: Node, via: Field, pPoint: UniqueID): LNode = {
      LNode(from match { case LNode(lfrom, _, _, _) => lfrom case _ => from }, via, pPoint, sig)
    }

    case object GBNode extends Node("Ngb", false) with GloballyReachableNode with SimpleNode {
      val types = TypeInfo.subtypeOf(definitions.ObjectClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }

    case object NNode extends Node("Null", true) with SimpleNode {
      val types = TypeInfo.empty
      val sig   = SigEntry.fromTypeInfo(types)
    }

    case object UNode extends Node("Unit", true) with SimpleNode {
      val types = TypeInfo.empty
      val sig   = SigEntry.fromTypeInfo(types)
    }

    case object StringLitNode extends Node("StringLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.StringClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object AnyLongLitNode extends Node("LongLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.LongClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case class LongLitNode(v: Long) extends Node(v+"l", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.LongClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object AnyIntLitNode extends Node("IntLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.IntClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case class IntLitNode(v: Int) extends Node(v.toString, true) with SimpleNode {
      val types = TypeInfo.exact(definitions.IntClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object FloatLitNode extends Node("FloatLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.FloatClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object ByteLitNode extends Node("ByteLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.ByteClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object CharLitNode extends Node("CharLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.CharClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object ShortLitNode extends Node("ShortLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.ShortClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object DoubleLitNode extends Node("DoubleLit", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.DoubleClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case object AnyBooleanLitNode extends Node("Boolean", true) with SimpleNode {
      val types = TypeInfo.exact(definitions.BooleanClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }
    case class BooleanLitNode(v: Boolean) extends Node(v.toString, true) with SimpleNode {
      val types = TypeInfo.exact(definitions.BooleanClass.tpe)
      val sig   = SigEntry.fromTypeInfo(types)
    }

    val unitSymbols: Set[Symbol] = 
      Set(definitions.UnitClass,
          definitions.BoxedUnitClass,
          definitions.BoxedUnitModule)


    def typeToLitNode(t: Type): Node = {
      val s = t.typeSymbol

      if (s == definitions.StringClass) {
        StringLitNode
      } else if (s == definitions.LongClass) {
        AnyLongLitNode
      } else if (s == definitions.IntClass) {
        AnyIntLitNode
      } else if (s == definitions.FloatClass) {
        FloatLitNode
      } else if (s == definitions.ByteClass) {
        ByteLitNode
      } else if (s == definitions.CharClass) {
        CharLitNode
      } else if (s == definitions.ShortClass) {
        ShortLitNode
      } else if (s == definitions.DoubleClass) {
        DoubleLitNode
      } else if (s == definitions.BooleanClass) {
        AnyBooleanLitNode
      } else if (unitSymbols(s)) {
        UNode
      } else {
        NNode
      }
    }

    def buildPureEffect(sym: Symbol): FunctionCFG = {
      val (args, argsTypes, retval) = sym.tpe match {
        case MethodType(argssym, tpe) =>
          (argssym.map(s => new CFGTrees.SymRef(s, NoUniqueID, s.tpe)), argssym.map(s => TypeInfo.subtypeOf(s.tpe)), new CFGTrees.TempRef("retval", NoUniqueID, tpe))

        case tpe =>
          (Seq(), Seq(), new CFGTrees.TempRef("retval", NoUniqueID, tpe))
      }

      var cfg = new FunctionCFG(sym, args, retval, true)

      var baseEnv    = new PTEnv()

      // 1) We add 'this'/'super'
      val thisNode = LVNode(cfg.mainThisRef, SigEntry.fromTypeInfo(TypeInfo.subtypeOf(cfg.mainThisRef.tpe)))
      baseEnv = baseEnv.addNode(thisNode).setL(cfg.mainThisRef, Set(thisNode))

      // 2) We add arguments
      for ((a, info) <- cfg.args zip argsTypes) {
        val aNode = if (isGroundTypeInfo(info)) {
            typeToLitNode(info.tpe)
          } else {
            LVNode(a, SigEntry.fromTypeInfo(info))
          }
        baseEnv = baseEnv.addNode(aNode).setL(a, Set(aNode))
      }

      // 3) return value
      val retInfo = TypeInfo.subtypeOf(retval.tpe)
      val retNode = if (isGroundTypeInfo(retInfo)) {
        typeToLitNode(retval.tpe)
      } else {
        INode(NoUniqueID, false, retval.tpe.typeSymbol)
      }

      baseEnv = baseEnv.addNode(retNode).setL(retval, Set(retNode))

      cfg += (cfg.entry, new CFGTrees.Effect(baseEnv, "Pure Effect of "+uniqueFunctionName(sym)) setTree EmptyTree, cfg.exit)

      cfg
    }

    sealed abstract class Edge(val v1: Node, val label: Field, val v2: Node) extends LabeledEdgeAbs[Field, Node] {
      override def toString() = v1+"-("+label+")->"+v2
    }

    object Edge {
      def unapply(e: Edge) = Some((e.v1, e.label, e.v2))
    }

    case class IEdge(_v1: Node, _label: Field, _v2: Node) extends Edge(_v1, _label, _v2)
    case class OEdge(_v1: Node, _label: Field, _v2: Node) extends Edge(_v1, _label, _v2)
    case class VEdge(_v1: VNode, _v2: Node) extends Edge(_v1, NoField, _v2)

    type PointToGraph = LabeledImmutableDirectedGraphImp[Field, Node, Edge]

    private def completeGraph(env: PTEnv) = {
        var newGraph = env.ptGraph

        // We complete the graph with local vars -> nodes association, for clarity
        for ((ref, nodes) <- env.locState; n <- nodes) {
          newGraph += VEdge(VNode(ref), n)
        }

        newGraph
    }

    def dumpPTE(env: PTEnv, dest: String) {
      reporter.debug("Dumping Effect to "+dest+"...")
      new PTDotConverter(env, "Effect").writeFile(dest)
    }

    private def dumpGraph(res: StringBuffer, env: PTEnv, prefix: String): PTDotConverter = {
      val clusterName = "cluster"+prefix;

      res append "subgraph "+clusterName+" {\n"
      res append "  label=\""+DotHelpers.escape(prefix)+"\";\n"
      res append "  color=\"gray\";\n"

      val ptdot = new PTDotConverter(env, "Effects", prefix)
      ptdot.drawGraph(res)

      if (env.isBottom) {
        res append "  bottom"+prefix+" [label=\"(Bottom)\", color=white]; "
      }

      res append "}\n"

      ptdot
    }

    def dumpDiff(oldEnv:   PTEnv,
                 newEnv:   PTEnv,
                 dest:        String) {
      reporter.debug("Dumping Diff Graphs to "+dest+"...")

      val res = new StringBuffer()

      res append "digraph D {\n"
      res append " label=\"\"\n"

      val ptIn  = dumpGraph(res, oldEnv,  "Old")
      val ptOut = dumpGraph(res, newEnv,  "New")

      res append "}\n"

      import java.io.{BufferedWriter, FileWriter}
      val out = new BufferedWriter(new FileWriter(dest))
      out.write(res.toString)
      out.close()
    }

    def dumpInlining(envInner:   PTEnv,
                     envOuter:   PTEnv,
                     envResult:  PTEnv,
                     mapInit:    Map[Node, Set[Node]],
                     mapResult:  Map[Node, Set[Node]],
                     dest:        String) {
      reporter.debug("Dumping Inlining Graphs to "+dest+"...")

      val res = new StringBuffer()

      res append "digraph D {\n"
      res append " label=\"\"\n"

      val ptIn  = dumpGraph(res, envInner,  "Inner")
      val ptOut = dumpGraph(res, envOuter,  "Outer")
      val ptRes = dumpGraph(res, envResult, "Result")

      for ((in, outs) <- mapInit; out <- outs) {
        res append DotHelpers.arrow(ptIn.vToS(in), ptOut.vToS(out), List("arrowhead=open", "color=red3"))
      }
      for ((in, outs) <- mapResult; out <- outs) {
        res append DotHelpers.arrow(ptIn.vToS(in), ptRes.vToS(out), List("arrowhead=open", "color="+DotHelpers.randomColor))
      }

      res append "}\n"

      import java.io.{BufferedWriter, FileWriter}
      val out = new BufferedWriter(new FileWriter(dest))
      out.write(res.toString)
      out.close()
    }

    class PTDotConverter(_graph: PointToGraph, _title: String, _prefix: String) extends DotConverter(_graph, _title, _prefix) {
      import utils.DotHelpers

      def this(env: PTEnv, _title: String, prefix: String = "") = 
        this(completeGraph(env), _title, prefix)

      def labelToString(f: Field): String = f.name.toString

      override def edgeToString(res: StringBuffer, e: Edge) {
        e match {
          case VEdge(v1, v2) => // Variable edge, used to draw graphs only (var -> nodes)
            res append DotHelpers.arrow(vToS(e.v1), vToS(e.v2), List("arrowhead=vee", "color=blue4"))
          case IEdge(v1, l, v2) =>
            res append DotHelpers.labeledArrow(vToS(e.v1), labelToString(e.label), vToS(e.v2))
          case OEdge(v1, l, v2) =>
            res append DotHelpers.labeledDashedArrow(vToS(e.v1), labelToString(e.label), vToS(e.v2))
        }
      }

      override def vertexToString(res: StringBuffer, v: Node) {
        //var opts = if(returnNodes contains v) List("shape=doublecircle") else List("shape=circle")
        var opts = List("fontsize=10")

        v match {
          case VNode(ref) => // Variable node, used to draw graphs only (var -> nodes)
            res append DotHelpers.invisNode(vToS(v), v.name, "fontcolor=blue4" :: opts)
          case LVNode(ref, _) =>
            res append DotHelpers.dashedNode(vToS(v), v.name+"\\n"+v.sig, "shape=rectangle" :: "color=green" :: opts)
          case LNode(_, _, _, _) =>
            res append DotHelpers.dashedNode(vToS(v), v.name+"\\n"+v.sig, "shape=rectangle" :: opts)
          case INode(pPoint, sgt, _) =>
            res append DotHelpers.node(vToS(v), v.name+"\\n"+v.sig, (if(sgt) "shape=rectangle" else "shape=box3d") ::opts)
          case OBNode(_) =>
            res append DotHelpers.node(vToS(v), v.name, "shape=rectangle" :: opts)
          case n: SimpleNode =>
            res append DotHelpers.node(vToS(v), v.name, "shape=rectangle" :: opts)
        }
      }
    }

    class PTGraphCopier extends GraphCopier[Field, Node, Edge] {
      override def copyNode(n: Node): Node = n match {
        case VNode(ref) =>
          n
        case LNode(fromNode, via, pPoint, sig) =>
          LNode(copyNode(fromNode), copyField(via), pPoint, copySigEntry(sig))
        case LVNode(ref, sig) =>
          LVNode(copyRef(ref), copySigEntry(sig))
        case INode(pPoint, sgt, sym) =>
          INode(pPoint, sgt, sym)
        case n @ OBNode(s) =>
          n
        case n: SimpleNode =>
          n
        case _ =>
          sys.error("Unnexpected node type at this point")
      }

      def copySigEntry(sig: SigEntry): SigEntry = {
        sig.withInfo(copyTypes(sig.info))
      }

      def copyRef(r: CFG.Ref): CFG.Ref = r

      def copyIEdge(ie: IEdge): IEdge =
          IEdge(copyNode(ie.v1), copyField(ie.label), copyNode(ie.v2))

      def copyOEdge(oe: OEdge): OEdge =
          OEdge(copyNode(oe.v1), copyField(oe.label), copyNode(oe.v2))

      override def copyEdge(e: Edge): Edge = e match {
        case ie: IEdge =>
          copyIEdge(ie)
        case oe: OEdge =>
          copyOEdge(oe)
        case _ =>
          sys.error("Unnexpected edge type at this point")
      }

      def copyField(f: Field): Field = f match {
        case NormalField(sym, info) =>
          NormalField(sym, copyTypes(info))
        case GhostField(sym, info) =>
          GhostField(sym, copyTypes(info))
      }

      def copyTypes(tpeInfo: TypeInfo): TypeInfo = tpeInfo

      def copyTypesWithMap(map: Map[Type, Set[Type]])(tpeInfo: TypeInfo): TypeInfo = {
        tpeInfo
      }
    }

  }
}
