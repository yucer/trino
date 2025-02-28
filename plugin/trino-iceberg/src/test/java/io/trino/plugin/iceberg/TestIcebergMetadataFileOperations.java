/*
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
package io.trino.plugin.iceberg;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import io.trino.Session;
import io.trino.filesystem.hdfs.HdfsFileSystemFactory;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.iceberg.TrackingFileSystemFactory.OperationContext;
import io.trino.plugin.iceberg.TrackingFileSystemFactory.OperationType;
import io.trino.plugin.tpch.TpchPlugin;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import io.trino.tpch.TpchTable;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.inject.util.Modules.EMPTY_MODULE;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.trino.plugin.hive.metastore.file.FileHiveMetastore.createTestingFileHiveMetastore;
import static io.trino.plugin.iceberg.TestIcebergMetadataFileOperations.FileType.MANIFEST;
import static io.trino.plugin.iceberg.TestIcebergMetadataFileOperations.FileType.METADATA_JSON;
import static io.trino.plugin.iceberg.TestIcebergMetadataFileOperations.FileType.SNAPSHOT;
import static io.trino.plugin.iceberg.TestIcebergMetadataFileOperations.FileType.fromFilePath;
import static io.trino.plugin.iceberg.TrackingFileSystemFactory.OperationType.INPUT_FILE_GET_LENGTH;
import static io.trino.plugin.iceberg.TrackingFileSystemFactory.OperationType.INPUT_FILE_NEW_STREAM;
import static io.trino.plugin.iceberg.TrackingFileSystemFactory.OperationType.OUTPUT_FILE_CREATE;
import static io.trino.plugin.iceberg.TrackingFileSystemFactory.OperationType.OUTPUT_FILE_CREATE_OR_OVERWRITE;
import static io.trino.plugin.iceberg.TrackingFileSystemFactory.OperationType.OUTPUT_FILE_LOCATION;
import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.testing.QueryAssertions.copyTpchTables;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;

@Test(singleThreaded = true) // e.g. trackingFileSystemFactory is shared mutable state
public class TestIcebergMetadataFileOperations
        extends AbstractTestQueryFramework
{
    private static final Session TEST_SESSION = testSessionBuilder()
            .setCatalog("iceberg")
            .setSchema("test_schema")
            .build();

    private TrackingFileSystemFactory trackingFileSystemFactory;

    @Override
    protected DistributedQueryRunner createQueryRunner()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog("iceberg")
                .setSchema("test_schema")
                .build();

        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session)
                // Tests that inspect MBean attributes need to run with just one node, otherwise
                // the attributes may come from the bound class instance in non-coordinator node
                .setNodeCount(1)
                .build();

        File baseDir = queryRunner.getCoordinator().getBaseDataDir().resolve("iceberg_data").toFile();
        HiveMetastore metastore = createTestingFileHiveMetastore(baseDir);

        trackingFileSystemFactory = new TrackingFileSystemFactory(new HdfsFileSystemFactory(HDFS_ENVIRONMENT));
        queryRunner.installPlugin(new TestingIcebergPlugin(Optional.of(metastore), Optional.of(trackingFileSystemFactory), EMPTY_MODULE));
        queryRunner.createCatalog("iceberg", "iceberg");
        queryRunner.installPlugin(new TpchPlugin());
        queryRunner.createCatalog("tpch", "tpch");

        queryRunner.execute("CREATE SCHEMA test_schema");

        copyTpchTables(queryRunner, "tpch", TINY_SCHEMA_NAME, session, TpchTable.getTables());
        return queryRunner;
    }

    @Test
    public void testCreateTable()
    {
        assertFileSystemAccesses("CREATE TABLE test_create (id VARCHAR, age INT)",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(METADATA_JSON, OUTPUT_FILE_CREATE), 1)
                        .addCopies(new FileOperation(METADATA_JSON, OUTPUT_FILE_LOCATION), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, OUTPUT_FILE_CREATE_OR_OVERWRITE), 1)
                        .addCopies(new FileOperation(SNAPSHOT, OUTPUT_FILE_LOCATION), 2)
                        .build());
    }

    @Test
    public void testCreateTableAsSelect()
    {
        assertFileSystemAccesses("CREATE TABLE test_create_as_select AS SELECT 1 col_name",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, OUTPUT_FILE_CREATE_OR_OVERWRITE), 1)
                        .addCopies(new FileOperation(MANIFEST, OUTPUT_FILE_LOCATION), 1)
                        .addCopies(new FileOperation(METADATA_JSON, OUTPUT_FILE_CREATE), 1)
                        .addCopies(new FileOperation(METADATA_JSON, OUTPUT_FILE_LOCATION), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, OUTPUT_FILE_CREATE_OR_OVERWRITE), 1)
                        .addCopies(new FileOperation(SNAPSHOT, OUTPUT_FILE_LOCATION), 2)
                        .build());
    }

    @Test
    public void testSelect()
    {
        assertUpdate("CREATE TABLE test_select AS SELECT 1 col_name", 1);
        assertFileSystemAccesses("SELECT * FROM test_select",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
    }

    @Test
    public void testSelectFromVersionedTable()
    {
        String tableName = "test_select_from_versioned_table";
        assertUpdate("CREATE TABLE " + tableName + " (id int, age int)");
        long v1SnapshotId = getLatestSnapshotId(tableName);
        assertUpdate("INSERT INTO " + tableName + " VALUES (2, 20)", 1);
        long v2SnapshotId = getLatestSnapshotId(tableName);
        assertUpdate("INSERT INTO " + tableName + "  VALUES (3, 30)", 1);
        long v3SnapshotId = getLatestSnapshotId(tableName);
        assertFileSystemAccesses("SELECT * FROM " + tableName + " FOR VERSION AS OF " + v1SnapshotId,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
        assertFileSystemAccesses("SELECT * FROM " + tableName + " FOR VERSION AS OF " + v2SnapshotId,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2)
                        .build());
        assertFileSystemAccesses("SELECT * FROM " + tableName + " FOR VERSION AS OF " + v3SnapshotId,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .build());
        assertFileSystemAccesses("SELECT * FROM " + tableName,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .build());
    }

    @Test
    public void testSelectFromVersionedTableWithSchemaEvolution()
    {
        String tableName = "test_select_from_versioned_table_with_schema_evolution";
        assertUpdate("CREATE TABLE " + tableName + " (id int, age int)");
        long v1SnapshotId = getLatestSnapshotId(tableName);
        assertUpdate("INSERT INTO " + tableName + " VALUES (2, 20)", 1);
        long v2SnapshotId = getLatestSnapshotId(tableName);
        assertUpdate("ALTER TABLE " + tableName + " ADD COLUMN address varchar");
        assertUpdate("INSERT INTO " + tableName + "  VALUES (3, 30, 'London')", 1);
        long v3SnapshotId = getLatestSnapshotId(tableName);
        assertFileSystemAccesses("SELECT * FROM " + tableName + " FOR VERSION AS OF " + v1SnapshotId,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
        assertFileSystemAccesses("SELECT * FROM " + tableName + " FOR VERSION AS OF " + v2SnapshotId,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2)
                        .build());
        assertFileSystemAccesses("SELECT * FROM " + tableName + " FOR VERSION AS OF " + v3SnapshotId,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .build());
        assertFileSystemAccesses("SELECT * FROM " + tableName,
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .build());
    }

    @Test
    public void testSelectWithFilter()
    {
        assertUpdate("CREATE TABLE test_select_with_filter AS SELECT 1 col_name", 1);
        assertFileSystemAccesses("SELECT * FROM test_select_with_filter WHERE col_name = 1",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
    }

    @Test
    public void testJoin()
    {
        assertUpdate("CREATE TABLE test_join_t1 AS SELECT 2 AS age, 'id1' AS id", 1);
        assertUpdate("CREATE TABLE test_join_t2 AS SELECT 'name1' AS name, 'id1' AS id", 1);

        assertFileSystemAccesses("SELECT name, age FROM test_join_t1 JOIN test_join_t2 ON test_join_t2.id = test_join_t1.id",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 8)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 8)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 2)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 2)
                        .build());
    }

    @Test
    public void testJoinWithPartitionedTable()
    {
        assertUpdate("CREATE TABLE test_join_partitioned_t1 (a BIGINT, b TIMESTAMP(6) with time zone) WITH (partitioning = ARRAY['a', 'day(b)'])");
        assertUpdate("CREATE TABLE test_join_partitioned_t2 (foo BIGINT)");
        assertUpdate("INSERT INTO test_join_partitioned_t2 VALUES(123)", 1);
        assertUpdate("INSERT INTO test_join_partitioned_t1 VALUES(123, current_date)", 1);

        assertFileSystemAccesses("SELECT count(*) FROM test_join_partitioned_t1 t1 join test_join_partitioned_t2 t2 on t1.a = t2.foo",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 8)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 8)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 2)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 2)
                        .build());
    }

    @Test
    public void testExplainSelect()
    {
        assertUpdate("CREATE TABLE test_explain AS SELECT 2 AS age", 1);

        assertFileSystemAccesses("EXPLAIN SELECT * FROM test_explain",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
    }

    @Test
    public void testShowStatsForTable()
    {
        assertUpdate("CREATE TABLE test_show_stats AS SELECT 2 AS age", 1);

        assertFileSystemAccesses("SHOW STATS FOR test_show_stats",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
    }

    @Test
    public void testShowStatsForPartitionedTable()
    {
        assertUpdate("CREATE TABLE test_show_stats_partitioned " +
                "WITH (partitioning = ARRAY['regionkey']) " +
                "AS SELECT * FROM tpch.tiny.nation", 25);

        assertFileSystemAccesses("SHOW STATS FOR test_show_stats_partitioned",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
    }

    @Test
    public void testShowStatsForTableWithFilter()
    {
        assertUpdate("CREATE TABLE test_show_stats_with_filter AS SELECT 2 AS age", 1);

        assertFileSystemAccesses("SHOW STATS FOR (SELECT * FROM test_show_stats_with_filter WHERE age >= 2)",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());
    }

    @Test
    public void testPredicateWithVarcharCastToDate()
    {
        assertUpdate("CREATE TABLE test_varchar_as_date_predicate(a varchar) WITH (partitioning=ARRAY['truncate(a, 4)'])");
        assertUpdate("INSERT INTO test_varchar_as_date_predicate VALUES '2001-01-31'", 1);
        assertUpdate("INSERT INTO test_varchar_as_date_predicate VALUES '2005-09-10'", 1);

        assertFileSystemAccesses("SELECT * FROM test_varchar_as_date_predicate",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 4)
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 4)
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());

        // CAST to date and comparison
        assertFileSystemAccesses("SELECT * FROM test_varchar_as_date_predicate WHERE CAST(a AS date) >= DATE '2005-01-01'",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2) // fewer than without filter
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2) // fewer than without filter
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());

        // CAST to date and BETWEEN
        assertFileSystemAccesses("SELECT * FROM test_varchar_as_date_predicate WHERE CAST(a AS date) BETWEEN DATE '2005-01-01' AND DATE '2005-12-31'",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2) // fewer than without filter
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2) // fewer than without filter
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());

        // conversion to date as a date function
        assertFileSystemAccesses("SELECT * FROM test_varchar_as_date_predicate WHERE date(a) >= DATE '2005-01-01'",
                ImmutableMultiset.builder()
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_GET_LENGTH), 2) // fewer than without filter
                        .addCopies(new FileOperation(MANIFEST, INPUT_FILE_NEW_STREAM), 2) // fewer than without filter
                        .addCopies(new FileOperation(METADATA_JSON, INPUT_FILE_NEW_STREAM), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_GET_LENGTH), 1)
                        .addCopies(new FileOperation(SNAPSHOT, INPUT_FILE_NEW_STREAM), 1)
                        .build());

        assertUpdate("DROP TABLE test_varchar_as_date_predicate");
    }

    private void assertFileSystemAccesses(@Language("SQL") String query, Multiset<Object> expectedAccesses)
    {
        resetCounts();
        getDistributedQueryRunner().executeWithQueryId(TEST_SESSION, query);
        assertThat(ImmutableMultiset.<Object>copyOf(getOperations())).containsExactlyInAnyOrderElementsOf(expectedAccesses);
    }

    private void resetCounts()
    {
        trackingFileSystemFactory.reset();
    }

    private Multiset<FileOperation> getOperations()
    {
        return trackingFileSystemFactory.getOperationCounts()
                .entrySet().stream()
                .flatMap(entry -> nCopies(entry.getValue(), new FileOperation(entry.getKey())).stream())
                .collect(toCollection(HashMultiset::create));
    }

    private long getLatestSnapshotId(String tableName)
    {
        return (long) computeScalar(format("SELECT snapshot_id FROM \"%s$snapshots\" ORDER BY committed_at DESC FETCH FIRST 1 ROW WITH TIES", tableName));
    }

    static class FileOperation
    {
        private final FileType fileType;
        private final OperationType operationType;

        public FileOperation(OperationContext operationContext)
        {
            this(fromFilePath(operationContext.getFilePath()), operationContext.getOperationType());
        }

        public FileOperation(FileType fileType, OperationType operationType)
        {
            this.fileType = requireNonNull(fileType, "fileType is null");
            this.operationType = requireNonNull(operationType, "operationType is null");
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            FileOperation that = (FileOperation) o;
            return fileType == that.fileType &&
                    operationType == that.operationType;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(fileType, operationType);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("fileType", fileType)
                    .add("operationType", operationType)
                    .toString();
        }
    }

    enum FileType
    {
        METADATA_JSON,
        MANIFEST,
        SNAPSHOT,
        /**/;

        public static FileType fromFilePath(String path)
        {
            if (path.endsWith("metadata.json")) {
                return METADATA_JSON;
            }
            if (path.contains("/snap-")) {
                return SNAPSHOT;
            }
            if (path.endsWith("-m0.avro")) {
                return MANIFEST;
            }
            throw new IllegalArgumentException("File not recognized: " + path);
        }
    }
}
