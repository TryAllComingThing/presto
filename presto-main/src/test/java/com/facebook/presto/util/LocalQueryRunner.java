package com.facebook.presto.util;

import com.facebook.presto.execution.ExchangePlanFragmentSource;
import com.facebook.presto.execution.QueryManagerConfig;
import com.facebook.presto.metadata.AbstractMetadata;
import com.facebook.presto.metadata.ColumnHandle;
import com.facebook.presto.metadata.DualTable;
import com.facebook.presto.metadata.FunctionHandle;
import com.facebook.presto.metadata.FunctionInfo;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.InternalTable;
import com.facebook.presto.metadata.InternalTableHandle;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.metadata.TableMetadata;
import com.facebook.presto.operator.AlignmentOperator;
import com.facebook.presto.operator.HackPlanFragmentSourceProvider;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.operator.OperatorStats;
import com.facebook.presto.operator.SourceHashProviderFactory;
import com.facebook.presto.split.DataStreamProvider;
import com.facebook.presto.split.InternalSplit;
import com.facebook.presto.split.Split;
import com.facebook.presto.sql.analyzer.AnalysisResult;
import com.facebook.presto.sql.analyzer.Analyzer;
import com.facebook.presto.sql.analyzer.Session;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.DistributedLogicalPlanner;
import com.facebook.presto.sql.planner.LocalExecutionPlanner;
import com.facebook.presto.sql.planner.LogicalPlanner;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.PlanPrinter;
import com.facebook.presto.sql.planner.SubPlan;
import com.facebook.presto.sql.planner.TableScanPlanFragmentSource;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Statement;
import com.facebook.presto.tpch.TpchDataStreamProvider;
import com.facebook.presto.tpch.TpchSchema;
import com.facebook.presto.tpch.TpchSplit;
import com.facebook.presto.tpch.TpchTableHandle;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import org.intellij.lang.annotations.Language;

import java.util.List;

import static com.facebook.presto.sql.analyzer.Session.DEFAULT_CATALOG;
import static com.facebook.presto.sql.analyzer.Session.DEFAULT_SCHEMA;
import static com.facebook.presto.util.MaterializedResult.materialize;
import static com.facebook.presto.util.TestingTpchBlocksProvider.readTpchRecords;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static org.testng.Assert.assertTrue;

public class LocalQueryRunner
{
    private final DataStreamProvider dataStreamProvider;
    private final Metadata metadata;
    private final Session session;

    public LocalQueryRunner(DataStreamProvider dataStreamProvider, Metadata metadata, Session session)
    {
        this.dataStreamProvider = checkNotNull(dataStreamProvider, "dataStreamProvider is null");
        this.metadata = checkNotNull(metadata, "metadata is null");
        this.session = checkNotNull(session, "session is null");
    }

    public MaterializedResult execute(@Language("SQL") String sql)
    {
        Statement statement = SqlParser.createStatement(sql);

        Analyzer analyzer = new Analyzer(session, metadata);

        AnalysisResult analysis = analyzer.analyze(statement);

        PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();
        PlanNode plan = new LogicalPlanner(session, metadata, idAllocator).plan(analysis);
        new PlanPrinter().print(plan, analysis.getTypes());

        SubPlan subplan = new DistributedLogicalPlanner(metadata, idAllocator).createSubplans(plan, analysis.getSymbolAllocator(), true);
        assertTrue(subplan.getChildren().isEmpty(), "Expected subplan to have no children");

        ImmutableMap.Builder<PlanNodeId, TableScanPlanFragmentSource> builder = ImmutableMap.builder();
        for (PlanNode source : subplan.getFragment().getSources()) {
            TableScanNode tableScan = (TableScanNode) source;
            Split split = createSplit(tableScan.getTable());
            builder.put(tableScan.getId(), new TableScanPlanFragmentSource(split));
        }

        DataSize maxOperatorMemoryUsage = new DataSize(50, MEGABYTE);
        LocalExecutionPlanner executionPlanner = new LocalExecutionPlanner(session, metadata,
                new HackPlanFragmentSourceProvider(dataStreamProvider, null, new QueryManagerConfig()),
                analysis.getTypes(),
                null,
                builder.build(),
                ImmutableMap.<PlanNodeId, ExchangePlanFragmentSource>of(),
                new OperatorStats(),
                new SourceHashProviderFactory(maxOperatorMemoryUsage),
                maxOperatorMemoryUsage
        );

        Operator operator = executionPlanner.plan(plan);

        return materialize(operator);
    }

    public static LocalQueryRunner createTpchLocalQueryRunner()
    {
        TestingTpchBlocksProvider tpchBlocksProvider = new TestingTpchBlocksProvider(ImmutableMap.of(
                "orders", readTpchRecords("orders"),
                "lineitem", readTpchRecords("lineitem")));

        DataStreamProvider dataProvider = new TpchDataStreamProvider(tpchBlocksProvider);
        Metadata metadata = TpchSchema.createMetadata();
        Session session = new Session(null, TpchSchema.CATALOG_NAME, TpchSchema.SCHEMA_NAME);

        return new LocalQueryRunner(dataProvider, metadata, session);
    }

    public static LocalQueryRunner createDualLocalQueryRunner()
    {
        return createDualLocalQueryRunner(new Session(null, DEFAULT_CATALOG, DEFAULT_SCHEMA));
    }

    public static LocalQueryRunner createDualLocalQueryRunner(Session session)
    {
        DataStreamProvider dataProvider = new DualTableDataStreamProvider();
        Metadata metadata = new DualTableMetadata();
        return new LocalQueryRunner(dataProvider, metadata, session);
    }

    private static Split createSplit(TableHandle handle)
    {
        if (handle instanceof TpchTableHandle) {
            return new TpchSplit((TpchTableHandle) handle);
        }
        if (handle instanceof InternalTableHandle) {
            return new InternalSplit((InternalTableHandle) handle);
        }
        throw new IllegalArgumentException("unsupported table handle: " + handle.getClass().getName());
    }

    private static class DualTableMetadata
            extends AbstractMetadata
    {
        private final FunctionRegistry functions = new FunctionRegistry();

        @Override
        public FunctionInfo getFunction(QualifiedName name, List<TupleInfo.Type> parameterTypes)
        {
            return functions.get(name, parameterTypes);
        }

        @Override
        public FunctionInfo getFunction(FunctionHandle handle)
        {
            return functions.get(handle);
        }

        @Override
        public TableMetadata getTable(String catalogName, String schemaName, String tableName)
        {
            checkArgument(tableName.equals(DualTable.NAME), "wrong table name: %s", tableName);
            return DualTable.getMetadata(catalogName, schemaName, tableName);
        }
    }

    private static class DualTableDataStreamProvider
            implements DataStreamProvider
    {
        @Override
        public Operator createDataStream(Split split, List<ColumnHandle> columns)
        {
            checkArgument(columns.size() == 1, "expected exactly one column");
            InternalTable table = DualTable.getInternalTable(DEFAULT_CATALOG, DEFAULT_SCHEMA, DualTable.NAME);
            return new AlignmentOperator(ImmutableList.of(table.getColumn(0)));
        }
    }
}