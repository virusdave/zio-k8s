package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.{ format, writeTextFile }
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import org.scalafmt.interfaces.Scalafmt
import zio.blocking.Blocking
import zio.{ Task, ZIO }
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.meta._

trait SubresourceClientGenerator {
  this: ModelGenerator =>

  def generateSubresourceAliases(
    scalafmt: Scalafmt,
    targetRoot: Path,
    subresources: Set[SubresourceId]
  ): ZIO[Blocking, Throwable, Set[Path]] = {
    val targetDir = targetRoot / "com" / "coralogix" / "zio" / "k8s" / "client" / "subresources"
    ZIO.foreach(subresources) { subid =>
      val (modelPkg, modelName) = splitName(subid.modelName)
      val src = subresourceSource(subid, modelPkg, modelName)
      val targetPkgDir = modelPkg.foldLeft(targetDir)(_ / _)
      val targetPath = targetPkgDir / (subid.name + ".scala")
      for {
        _ <- Files.createDirectories(targetPkgDir)
        _ <- writeTextFile(targetPath, src)
        _ <- format(scalafmt, targetPath)
      } yield targetPath
    }
  }

  def subresourceSource(
    subresource: SubresourceId,
    pkg: Vector[String],
    modelName: String
  ): String = {
    val packageTerm = (Vector("com", "coralogix", "zio", "k8s", "client", "subresources") ++ pkg)
      .mkString(".")
      .parse[Term]
      .get
      .asInstanceOf[Term.Ref]
    val capName = subresource.name.capitalize
    val namespacedT = Type.Name(s"Namespaced${capName}Subresource")
    val namespacedTerm = Term.Name(s"Namespaced${capName}Subresource")
    val clusterT = Type.Name(s"Cluster${capName}Subresource")
    val clusterTerm = Term.Name(s"Cluster${capName}Subresource")
    val modelT =
      if (pkg.nonEmpty) {
        val modelNs = pkg.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
        Type.Select(modelNs, Type.Name(modelName))
      } else {
        Type.Name(modelName)
      }

    val getTerm = Term.Name(s"get$capName")
    val putTerm = Term.Name(s"replace$capName")
    val postTerm = Term.Name(s"create$capName")
    val asGenericTerm = Term.Name(s"asGeneric${capName}Subresource")

    val nameLit = Lit.String(subresource.name)

    val clusterDefs = subresource.actionVerbs.toList.flatMap {
      case "get"  =>
        List(q"""
          def $getTerm(name: String): ZIO[Any, K8sFailure, $modelT]
          """)
      case "put"  =>
        List(q"""
          def $putTerm(name: String,
                             updatedValue: $modelT,
                             dryRun: Boolean = false
                            ): IO[K8sFailure, $modelT]
           """)
      case "post" =>
        List(q"""
           def $postTerm(name: String,
                         value: $modelT,
                         dryRun: Boolean = false): IO[K8sFailure, $modelT]
         """)
      case _      => List.empty
    }

    val namespacedDefs = subresource.actionVerbs.toList.flatMap {
      case "get"  =>
        List(q"""
          def $getTerm(name: String, namespace: K8sNamespace): ZIO[Any, K8sFailure, $modelT]
          """)
      case "put"  =>
        List(q"""
          def $putTerm(name: String,
                             updatedValue: $modelT,
                             namespace: K8sNamespace,
                             dryRun: Boolean = false
                            ): IO[K8sFailure, $modelT]
           """)
      case "post" =>
        List(q"""
           def $postTerm(name: String,
                         value: $modelT,
                         namespace: K8sNamespace,
                         dryRun: Boolean = false): IO[K8sFailure, $modelT]
         """)
      case _      => List.empty
    }

    q"""package $packageTerm {

        import com.coralogix.zio.k8s.model._
        import com.coralogix.zio.k8s.client.K8sFailure
        import com.coralogix.zio.k8s.client.model.{K8sCluster, K8sNamespace, ResourceMetadata}
        import com.coralogix.zio.k8s.client.Subresource
        import com.coralogix.zio.k8s.client.impl.SubresourceClient
        import sttp.capabilities.WebSockets
        import sttp.capabilities.zio.ZioStreams
        import sttp.client3.SttpBackend
        import zio._

        trait $namespacedT[T] {
          val $asGenericTerm: Subresource[$modelT]

          ..$namespacedDefs
        }

        object $namespacedTerm {
          def makeClient[T : Tag : ResourceMetadata](backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster): SubresourceClient[$modelT] =
            new SubresourceClient[$modelT](implicitly[ResourceMetadata[T]].resourceType, cluster, backend, $nameLit)
        }

        trait $clusterT[T] {
          val $asGenericTerm: Subresource[$modelT]

          ..$clusterDefs
        }

        object $clusterTerm {
          def makeClient[T : Tag : ResourceMetadata](backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster): SubresourceClient[$modelT] =
            new SubresourceClient[$modelT](implicitly[ResourceMetadata[T]].resourceType, cluster, backend, $nameLit)
        }
        }
     """.toString
  }
}
