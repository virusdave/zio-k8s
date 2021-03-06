package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.K8sCluster
import sttp.model.Uri
import zio.blocking.Blocking
import zio.config._
import zio.config.derivation.name
import zio.config.magnolia.DeriveConfigDescriptor.Descriptor
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.{ Has, Task, ZIO, ZLayer, ZManaged }

import java.io.{ FileInputStream, IOException, InputStream }
import java.nio.charset.StandardCharsets
import java.security.cert.{ CertificateFactory, X509Certificate }
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ SSLContext, TrustManager, TrustManagerFactory, X509TrustManager }

package object config {
  case class K8sClusterConfig(
    host: Uri,
    token: Option[String],
    @name("token-file") tokenFile: Path
  )

  case class K8sClientConfig(
    insecure: Boolean, // for testing with minikube
    debug: Boolean,
    cert: Path
  )

  implicit val uriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFail(
      s => Uri.parse(s),
      (uri: Uri) => Right(uri.toString)
    )

  implicit val pathDescriptor: Descriptor[Path] =
    Descriptor[String].transform(
      s => Path(s),
      (path: Path) => path.toString()
    )

  val k8sCluster: ZLayer[Blocking with Has[K8sClusterConfig], IOException, Has[K8sCluster]] =
    (for {
      config <- getConfig[K8sClusterConfig]
      result <- config.token match {
                  case Some(token) if token.nonEmpty =>
                    // Explicit API token
                    ZIO.succeed(
                      K8sCluster(
                        host = config.host,
                        token = token
                      )
                    )
                  case _                             =>
                    // No explicit token, loading from file
                    Files
                      .readAllBytes(config.tokenFile)
                      .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
                      .map { token =>
                        K8sCluster(
                          host = config.host,
                          token = token
                        )
                      }
                }
    } yield result).toLayer

  private[client] def insecureSSLContext(): Task[SSLContext] = {
    val trustAllCerts = Array[TrustManager](new X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      override def getAcceptedIssuers: Array[X509Certificate] = null
    })
    Task.effect {
      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(null, trustAllCerts, new SecureRandom())
      sslContext
    }
  }

  private[client] def secureSSLContext(certFile: Path): Task[SSLContext] =
    ZManaged.fromAutoCloseable(Task.effect(new FileInputStream(certFile.toFile))).use {
      certStream =>
        for {
          trustStore    <- createTrustStore(certStream)
          trustManagers <- createTrustManagers(trustStore)
          sslContext    <- createSslContext(trustManagers)
        } yield sslContext
    }

  private def createTrustStore(pemInputStream: InputStream): Task[KeyStore] =
    Task.effect {
      val certFactory = CertificateFactory.getInstance("X509")
      val cert = certFactory.generateCertificate(pemInputStream).asInstanceOf[X509Certificate]
      val trustStore = KeyStore.getInstance("JKS")
      trustStore.load(null)
      val alias = cert.getSubjectX500Principal.getName
      trustStore.setCertificateEntry(alias, cert)
      trustStore
    }

  private def createTrustManagers(trustStore: KeyStore): Task[Array[TrustManager]] =
    Task.effect {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(trustStore)
      tmf.getTrustManagers
    }

  private def createSslContext(trustManagers: Array[TrustManager]): Task[SSLContext] =
    Task.effect {
      val sslContext = SSLContext.getInstance("TLSv1.2")
      sslContext.init(null, trustManagers, new SecureRandom())
      sslContext
    }
}
