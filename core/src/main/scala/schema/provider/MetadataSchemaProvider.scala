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
package schema.provider

import cats.implicits._
import cats.{~>, MonadError}
import com.datastax.driver.core._
import freestyle.cassandra.api.ClusterAPI
import freestyle.{FreeS, _}
import freestyle.cassandra.schema.provider.metadata.SchemaConversions
import freestyle.cassandra.schema.SchemaDefinition

import scala.collection.JavaConverters._
import scala.language.postfixOps

class MetadataSchemaProvider[M[_]](cluster: Cluster)(implicit H: FSHandler[ClusterAPI.Op, M])
    extends SchemaDefinitionProvider[M]
    with SchemaConversions {

  def extractTables(keyspaceMetadata: KeyspaceMetadata): List[AbstractTableMetadata] =
    keyspaceMetadata.getTables.asScala.toList

  def extractIndexes(tableMetadataList: List[AbstractTableMetadata]): List[IndexMetadata] =
    tableMetadataList.flatMap {
      case (t: TableMetadata) => t.getIndexes.asScala.toList
      case _                  => Nil
    }

  def extractUserTypes(keyspaceMetadata: KeyspaceMetadata): List[UserType] =
    keyspaceMetadata.getUserTypes.asScala.toList

  override def schemaDefinition(implicit M: MonadError[M, Throwable]): M[SchemaDefinition] = {

    def guarantee[F[_], A](fa: F[A], finalizer: F[Unit])(
        implicit M: MonadError[F, Throwable]): F[A] =
      M.flatMap(M.attempt(fa)) { e =>
        M.flatMap(finalizer)(_ => e.fold(M.raiseError, M.pure))
      }

    def metadataF[F[_]](implicit clusterAPI: ClusterAPI[F]): FreeS[F, Metadata] =
      clusterAPI.connect *> clusterAPI.metadata

    def closeF[F[_]](implicit clusterAPI: ClusterAPI[F]): FreeS[F, Unit] =
      clusterAPI.close

    M.flatMap(guarantee(metadataF[ClusterAPI.Op].interpret[M], closeF[ClusterAPI.Op].interpret[M])) {
      metadata =>
        val keyspaceList: List[KeyspaceMetadata]   = metadata.getKeyspaces.asScala.toList
        val tableList: List[AbstractTableMetadata] = keyspaceList.flatMap(extractTables)
        val indexList: List[IndexMetadata]         = extractIndexes(tableList)
        val userTypeList: List[UserType]           = keyspaceList.flatMap(extractUserTypes)

        M.map4(
          keyspaceList.traverse(toCreateKeyspace[M]),
          tableList.traverse(toCreateTable[M]),
          indexList.traverse(toCreateIndex[M](_)),
          userTypeList.traverse(toUserType[M])
        )(_ ++ _ ++ _ ++ _)
    }
  }
}

object MetadataSchemaProvider {

  implicit def metadataSchemaProvider[M[_]](
      implicit cluster: Cluster,
      H: ClusterAPI.Op ~> M): SchemaDefinitionProvider[M] =
    new MetadataSchemaProvider[M](cluster)

}