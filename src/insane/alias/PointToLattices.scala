package insane
package alias

import utils._
import utils.Reporters._
import CFG._

trait PointToLattices extends PointToGraphsDefs {
  self: AnalysisComponent =>

  import global._
  import PointToGraphs._

  object PointToLattice extends dataflow.LatticeAbs[PTEnv] {
    val bottom = BottomPTEnv

    def join(_envs: PTEnv*): PTEnv = {
      var envs = _envs.filterNot(_.isBottom)

      if (envs.isEmpty) {
        return BottomPTEnv
      } else if(envs.tail.isEmpty) {
        return envs.head
      }

      for ((e,i) <- envs.zipWithIndex) {
        val dest = "err-"+i+".dot"
        new PTDotConverter(e, "Flat Effect").writeFile(dest)
      }

      println("Size: "+envs.size)

      var newNodes  = envs.flatMap(_.ptGraph.V).toSet

      /**
       * If a non-singleton INode is present in one env and a singleton node is
       * present in another, we need to replace the singleton node with the non
       * singleton one
       */
      for ((n, sNode) <- newNodes.collect{ case i @ INode(pPoint, false, types) => (i, INode(pPoint, true, types)) } if newNodes contains sNode) {
        envs = for (e <- envs) yield {
          if (e.ptGraph.V contains sNode) {
            e.replaceNode(sNode, Set(n))
          } else {
            e
          }
        }
      }

      newNodes  = envs.flatMap(_.ptGraph.V).toSet
      var newIEdges = envs.flatMap(_.iEdges).toSet
      var newOEdges = envs.flatMap(_.oEdges).toSet

      /**
       * When merging environment, we need to take special care in case one
       * write edge is not present in the other envs, in that case, it
       * consists of a weak update in the resulting env.
       */

      // 1) We find all nodes that are shared between all envs
      val commonNodes = envs.map(_.ptGraph.V).reduceRight(_ & _)

      // 2) We find all the pair (v1, f) that are not in every env's iEdges
      val allPairs = newIEdges.map(ed => (ed.v1, ed.label)).toSet

      val commonPairs = envs.map(_.iEdges.map(ed => (ed.v1, ed.label)).toSet).reduceRight(_ & _)

      for ((v1, field) <- allPairs -- commonPairs if commonNodes contains v1) {
        v1 match {
          case _: INode =>
            settings.ifDebug {
              reporter.warn("Odd, we have an Inside node with missing fields: "+v1+" and field "+field+". Might be caused by join performed during monitonicity checks")
            }
          case _ =>
            safeLNode(v1, field, new UniqueID(0)) match {
              case Some(lNode) =>
                for (nodeToAdd <- findSimilarLNodes(lNode, newNodes)) {
                  newNodes  += nodeToAdd
                  newIEdges += IEdge(v1, field, nodeToAdd)
                  newOEdges += OEdge(v1, field, nodeToAdd)
                }
              case None =>
                reporter.warn("Unable to create LNode from "+v1+"["+v1.types+"] via "+field+" upon union!")
                sys.error("plop");
            }
        }
      }

      var newGraph = new PointToGraph(newNodes, newOEdges ++ newIEdges)
      
      val env = PTEnv(
        newGraph,
        envs.flatMap(_.locState.keySet).toSet.map((k: CFG.Ref) => k -> (envs.map(e => e.locState(k)).reduceRight(_ ++ _))).toMap.withDefaultValue(Set()),
        newIEdges,
        newOEdges,
        envs.exists(_.isPartial),
        envs.forall(_.isBottom),
        envs.forall(_.isEmpty))

      env
    }

  }
}
