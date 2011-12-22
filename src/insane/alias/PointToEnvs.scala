package insane
package alias

import utils._
import utils.Reporters._
import CFG._

trait PointToEnvs extends PointToGraphsDefs {
  self: AnalysisComponent =>

  import global._
  import PointToGraphs._

  case class PTEnv(ptGraph: PointToGraph,
                 locState: Map[CFG.Ref, Set[Node]],
                 iEdges: Set[IEdge],
                 oEdges: Set[OEdge],
                 isPartial: Boolean,
                 isBottom: Boolean) extends dataflow.EnvAbs[PTEnv] {

    def this(isPartial: Boolean = false, isBottom: Boolean = false) =
      this(new PointToGraph(),
           Map().withDefaultValue(Set()),
           Set(),
           Set(),
           isPartial,
           isBottom)

    def getAllTargetsUsing(edges: Traversable[Edge])(from: Set[Node], via: Field): Set[Node] = {
      edges.collect{ case Edge(v1, f, v2) if (from contains v1) && (f == via) => v2 }.toSet
    }

    def clean() = copy(locState = Map().withDefaultValue(Set()))

    val getAllTargets   = getAllTargetsUsing(ptGraph.E)_
    val getWriteTargets = getAllTargetsUsing(iEdges)_
    val getReadTargets  = getAllTargetsUsing(oEdges)_

    def setL(ref: CFG.Ref, nodes: Set[Node]) = {
      copy(locState = locState + (ref -> nodes), isBottom = false)
    }

    def getL(ref: CFG.Ref, readOnly: Boolean): (PTEnv, Set[Node]) = {
      if (locState contains ref) {
        (this, locState(ref))
      } else {
        if (readOnly) {
          reporter.error("Consistency problem: local field accessed without associated nodes in a comp-sub-graph while in read-only context");
          (this, locState(ref))
        } else {
          val n = LVNode(ref, ObjectSet.subtypesOf(ref.tpe))
          (addNode(n).setL(ref, Set(n)), Set(n))
        }
      }
    }

    def replaceNode(from: Node, toNodes: Set[Node]) = {
      assert(!(toNodes contains from), "Recursively replacing "+from+" with "+toNodes.mkString("{", ", ", "}")+"!")

      var newEnv = copy(ptGraph = ptGraph - from ++ toNodes, isBottom = false)

      // Update iEdges
      for (iEdge @ IEdge(v1, lab, v2) <- iEdges if v1 == from || v2 == from; to <- toNodes) {
        val newIEdge = IEdge(if (v1 == from) to else v1, lab, if (v2 == from) to else v2)

        newEnv = newEnv.copy(ptGraph = ptGraph - iEdge + newIEdge, iEdges = iEdges - iEdge + newIEdge)
      }

      // Update oEdges
      for (oEdge @ OEdge(v1, lab, v2) <- oEdges if v1 == from || v2 == from; to <- toNodes) {
        val newOEdge = OEdge(if (v1 == from) to else v1, lab, if (v2 == from) to else v2)

        newEnv = newEnv.copy(ptGraph = ptGraph - oEdge + newOEdge, oEdges = oEdges - oEdge + newOEdge)
      }

      // Update locState
      newEnv = newEnv.copy(locState = locState.map{ case (ref, nodes) => ref -> (if (nodes contains from) nodes - from ++ toNodes else nodes) }.withDefaultValue(Set()))

      newEnv
    }

    def addNode(node: Node) =
      copy(ptGraph = ptGraph + node, isBottom = false)

    lazy val loadNodes: Set[LNode] = {
      ptGraph.V.collect { case l: LNode => l }
    }

    /**
     * Corresponds to:
     *   to = {..from..}.field @UniqueID
     */
    def read(from: Set[Node], field: Field, to: CFG.Ref, uniqueID: UniqueID) = {

      var res = this

      var pointResults = Set[Node]()

      for (node <- from) {
        val writeTargets = getWriteTargets(Set(node), field)

        val pointed = if (writeTargets.isEmpty) {
          getReadTargets(Set(node), field)
        } else {
          writeTargets
        }

        if (pointed.isEmpty) {
          safeLNode(node, field, uniqueID) match {
            case Some(lNode) =>
              res = res.addNode(lNode).addOEdge(node, field, lNode)
              pointResults += lNode
            case None =>
              reporter.error("Unable to create LNode for read from "+node+" via "+field)
              sys.exit(0)
          }
        } else {
          pointResults ++= pointed
        }
      }

      res.setL(to, pointResults)
    }

    /**
     * Corresponds to:
     *   {..from..}.field = {..to..} @UniqueID
     */
    def write(from: Set[Node], field: Field, to: Set[Node], allowStrongUpdates: Boolean) = {
      if (from.size == 0) {
        reporter.error("Writing with an empty {..from..} set!")
      }

      if (to.size == 0) {
        reporter.error("Writing with an empty {..to..} set!")
      }

      var newEnv = this

      val isStrong = from.forall(_.isSingleton) && from.size == 1 && allowStrongUpdates

      if (isStrong) {
        // If strong update:

        // 1) We remove all previous write edges
        newEnv = newEnv.removeIEdges(from, field, getWriteTargets(from, field))

        // 2) We add back only the new write edge
        newEnv = newEnv.addIEdges(from, field, to)
      } else {
        // If weak update:

        // For each actual source node:
        for (node <- from) {
          // 1) We check for an old node reachable
          val writeTargets = getWriteTargets(Set(node), field)

          val previouslyPointed = if (writeTargets.isEmpty) {
            getReadTargets(Set(node), field)
          } else {
            writeTargets
          }

          if (previouslyPointed.isEmpty) {
            // We need to add the artificial load node, as it represents the old state
            safeLNode(node, field, new UniqueID(0)) match {
              case Some(lNode) =>
                newEnv = newEnv.addNode(lNode).addOEdge(node, field, lNode).addIEdge(node, field, lNode)
              case None =>
                reporter.error("Unable to create LNode for write from "+node+" via "+field)
            }
          }

          // 2) We link that to node via a write edge
          newEnv = newEnv.addIEdges(Set(node), field, previouslyPointed ++ to)
        }
      }

      newEnv
    }

    def addOEdge(v1: Node, field: Field, v2: Node) = addOEdges(Set(v1), field, Set(v2))

    def addOEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      var newGraph = ptGraph
      var oEdgesNew = oEdges
      for (v1 <- lv1; v2 <- lv2) {
        val e = OEdge(v1, field, v2)
        newGraph += e
        oEdgesNew += e
      }
      copy(ptGraph = newGraph, oEdges = oEdgesNew, isBottom = false)
    }

    def addIEdge(v1: Node, field: Field, v2: Node) = addIEdges(Set(v1), field, Set(v2))

    def addIEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      var newGraph = ptGraph
      var iEdgesNew = iEdges
      for (v1 <- lv1; v2 <- lv2) {
        val e = IEdge(v1, field, v2)
        newGraph += e
        iEdgesNew += e
      }
      copy(ptGraph = newGraph, iEdges = iEdgesNew, isBottom = false)
    }

    def removeIEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      val toRemove = iEdges.filter(e => lv1.contains(e.v1) && lv2.contains(e.v2) && e.label == field)

      copy(ptGraph = (ptGraph /: toRemove) (_ - _), iEdges = iEdges -- toRemove, isBottom = false)
    }

    def removeOEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      val toRemove = oEdges.filter(e => lv1.contains(e.v1) && lv2.contains(e.v2) && e.label == field)

      copy(ptGraph = (ptGraph /: toRemove) (_ - _), oEdges = oEdges -- toRemove, isBottom = false)
    }

    def addGlobalNode() = {
      copy(ptGraph = ptGraph + GBNode, isBottom = false)
    }

    def stripTypeInconsistencies = {
      // TODO
      this
    }

    def modifiesClause: ModifyClause = {
      import scala.collection.mutable.Stack

      /**
       * Check if there is any reachable IEdge from
       * 1) Params
       * 2) Global Objects
       **/

      var seen    = Set[Node]()
      var effects = Set[ModifyClauseEffect]()

      for (n <- ptGraph.V) n match {
        // At this point, remaining LVNodes are parameters
        case _: LVNode | _: GloballyReachableNode =>
          visitRoot(n)
        case _ =>
      }

      def visitRoot(n: Node) {
        def visit(n: Node, root: Node, path: List[Field]) {

          seen += n

          for (e @ Edge(v1, via, v2) <- ptGraph.outEdges(n)) {
            val newPath = via :: path

            e match {
              case _: IEdge =>
                effects += ModifyClauseEffect(newPath.reverse, root)
              case _ =>
            }

            if (!seen(v2)) {
              visit(v2, root, newPath)
            }
          }
        }

        visit(n, n, Nil)
      }

      ModifyClause(effects)
    }

    def duplicate = this

    def getNodes(sv: CFG.SimpleValue, readonly: Boolean = false): (PTEnv, Set[Node]) = sv match {
      case r2: CFG.Ref       => getL(r2, readonly)
      case n : CFG.Null      => (this, Set(NNode))
      case u : CFG.Unit      => (this, Set())
      case _: CFG.StringLit  => (this, Set(StringLitNode))
      case _: CFG.BooleanLit => (this, Set(BooleanLitNode))
      case _: CFG.LongLit    => (this, Set(LongLitNode))
      case _: CFG.IntLit     => (this, Set(IntLitNode))
      case _: CFG.CharLit    => (this, Set(CharLitNode))
      case _: CFG.ByteLit    => (this, Set(ByteLitNode))
      case _: CFG.FloatLit   => (this, Set(FloatLitNode))
      case _: CFG.DoubleLit  => (this, Set(DoubleLitNode))
      case _: CFG.ShortLit   => (this, Set(ShortLitNode))
    }

    def cleanLocState(fun: FunctionCFG): PTEnv = {
      // We remove locstate assignments for complete (non-partial graphs) other
      // than for args, this, or retval other should never be needed
      copy(locState = locState filter {
        case (r, nodes) =>
          val kind = r match {
            case tr: CFG.ThisRef =>
              fun.thisRefs contains tr
            case sr: CFG.SuperRef =>
              fun.superRefs contains sr
            case r =>
              fun.args contains r
          }

          kind || (r == fun.retval)
        })
    }

    def cleanUnreachable(fun: FunctionCFG): PTEnv = {
      // We want to remove any node, edge, that is not reachableo
      // Perform DFS on the graph from every reachable nodes, mark nodes and
      // edges, remove the rest
      val graph = ptGraph

      var markedNodes = Set[Node]() ++ ((fun.args++fun.thisRefs++fun.superRefs++Set(fun.retval)) flatMap locState) ++
                  ((GBNode :: NNode :: NNode :: BooleanLitNode :: LongLitNode :: DoubleLitNode :: StringLitNode :: IntLitNode :: ByteLitNode :: CharLitNode :: FloatLitNode :: ShortLitNode :: Nil) filter (graph.V contains _))

      var markedEdges      = Set[Edge]()
      var queue            = markedNodes.toList

      while (!queue.isEmpty) {
        val n = queue.head
        queue = queue.tail

        for (e <- graph.outEdges(n)) {
          markedEdges += e

          if (!(markedNodes contains e.v2)) {
            markedNodes += e.v2

            queue = e.v2 :: queue
          }
        }
      }

      new PTEnv(new PointToGraph(markedNodes, markedEdges),
                locState,
                markedEdges.collect{ case e: IEdge => e },
                markedEdges.collect{ case e: OEdge => e },
                isPartial,
                isBottom);
    }
  }

  object BottomPTEnv extends PTEnv(false, true)

  class PTEnvCopier() {
    val graphCopier: GraphCopier = new GraphCopier

    def copyLocRef(ref: CFG.Ref): CFG.Ref = ref

    def copy(env: PTEnv): PTEnv = {
      PTEnv(
        graphCopier.copy(env.ptGraph),
        env.locState.foldLeft(Map[CFG.Ref, Set[Node]]().withDefaultValue(Set())){ case (map, (r, v)) => 
          val nk = copyLocRef(r)
          map + (nk -> (v.map(graphCopier.copyNode _) ++ map(nk)))
        },
        env.iEdges.map(graphCopier.copyIEdge _),
        env.oEdges.map(graphCopier.copyOEdge _),
        env.isPartial,
        env.isBottom
      )
    }
  }

  class PTEnvReplacer(typeMap: Map[Type, Type], symbolMap: Map[Symbol, Symbol]) extends PTEnvCopier {
    def newSymbol(s: Symbol) = symbolMap.getOrElse(s, s)
    def newType(t: Type)     = typeMap.getOrElse(t, t)

    override val graphCopier = new GraphCopier {
      override def copyNode(n: Node) = n match {
        case OBNode(s) =>
          OBNode(newSymbol(s))
        case _ =>
          super.copyNode(n)
      }

      override def copyTypes(oset: ObjectSet): ObjectSet = {
        ObjectSet(oset.subtypesOf.map(newType _), oset.exactTypes.map(newType _))
      }
    }

  }

}
