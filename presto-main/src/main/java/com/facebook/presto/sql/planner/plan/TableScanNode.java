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
package com.facebook.presto.sql.planner.plan;

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.Partition;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.TupleDomain;
import com.facebook.presto.sql.planner.DomainUtils;
import com.facebook.presto.sql.planner.Symbol;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

@Immutable
public class TableScanNode
        extends PlanNode
{
    private final TableHandle table;
    private final List<Symbol> outputSymbols;
    private final Map<Symbol, ColumnHandle> assignments; // symbol -> column
    private final Optional<GeneratedPartitions> generatedPartitions;
    private final boolean partitionsDroppedBySerialization;
    private final TupleDomain partitionDomainSummary;

    public TableScanNode(PlanNodeId id,  TableHandle table, List<Symbol> outputSymbols, Map<Symbol, ColumnHandle> assignments, Optional<GeneratedPartitions> generatedPartitions)
    {
        this(id, table, outputSymbols, assignments, generatedPartitions, false);
    }

    @JsonCreator
    public TableScanNode(@JsonProperty("id") PlanNodeId id,
            @JsonProperty("table") TableHandle table,
            @JsonProperty("outputSymbols") List<Symbol> outputSymbols,
            @JsonProperty("assignments") Map<Symbol, ColumnHandle> assignments)
    {
        this(id, table, outputSymbols, assignments, Optional.<GeneratedPartitions>absent(), true);
    }

    private TableScanNode(PlanNodeId id, TableHandle table, List<Symbol> outputSymbols, Map<Symbol, ColumnHandle> assignments, Optional<GeneratedPartitions> generatedPartitions, boolean partitionsDroppedBySerialization)
    {
        super(id);

        checkNotNull(table, "table is null");
        checkNotNull(outputSymbols, "outputSymbols is null");
        checkNotNull(assignments, "assignments is null");
        checkArgument(assignments.keySet().containsAll(outputSymbols), "assignments does not cover all of outputSymbols");
        checkArgument(!assignments.isEmpty(), "assignments is empty");
        checkNotNull(generatedPartitions, "generatedPartitions is null");

        this.table = table;
        this.outputSymbols = ImmutableList.copyOf(outputSymbols);
        this.assignments = ImmutableMap.copyOf(assignments);
        this.generatedPartitions = generatedPartitions;
        this.partitionsDroppedBySerialization = partitionsDroppedBySerialization;
        this.partitionDomainSummary = computePartitionsDomainSummary(generatedPartitions);
        checkArgument(partitionDomainSummary.isNone() || assignments.values().containsAll(partitionDomainSummary.getDomains().keySet()), "Assignments do not include all of the ColumnHandles specified by the Partitions");
    }

    @JsonProperty("table")
    public TableHandle getTable()
    {
        return table;
    }

    @JsonProperty("outputSymbols")
    public List<Symbol> getOutputSymbols()
    {
        return outputSymbols;
    }

    @JsonProperty("assignments")
    public Map<Symbol, ColumnHandle> getAssignments()
    {
        return assignments;
    }

    public Optional<GeneratedPartitions> getGeneratedPartitions()
    {
        // If this exception throws, then we might want to consider making Partitions serializable by Jackson
        checkState(!partitionsDroppedBySerialization, "Can't access partitions after passing through serialization");
        return generatedPartitions;
    }

    public TupleDomain getPartitionsDomainSummary()
    {
        return partitionDomainSummary;
    }

    private static TupleDomain computePartitionsDomainSummary(Optional<GeneratedPartitions> generatedPartitions)
    {
        if (!generatedPartitions.isPresent()) {
            return TupleDomain.all();
        }

        TupleDomain tupleDomain = TupleDomain.none();
        for (Partition partition : generatedPartitions.get().getPartitions()) {
            tupleDomain = tupleDomain.columnWiseUnion(partition.getTupleDomain());
        }
        return tupleDomain;
    }

    @JsonProperty("partitionDomainSummary")
    public String getPrintablePartitionDomainSummary()
    {
        // Since partitions are not serializable, we can provide an additional jackson field purely for information purposes (i.e. for logging)
        // If partitions ever become serializable, we can get rid of this method
        return DomainUtils.printableTupleDomainWithSymbols(partitionDomainSummary, assignments);
    }

    public List<PlanNode> getSources()
    {
        return ImmutableList.of();
    }

    public <C, R> R accept(PlanVisitor<C, R> visitor, C context)
    {
        return visitor.visitTableScan(this, context);
    }

    public final static class GeneratedPartitions
    {
        private final TupleDomain tupleDomainInput; // The TupleDomain used to generate the current list of Partitions
        private final List<Partition> partitions;

        public GeneratedPartitions(TupleDomain tupleDomainInput, List<Partition> partitions)
        {
            this.tupleDomainInput = checkNotNull(tupleDomainInput, "tupleDomainInput is null");
            this.partitions = ImmutableList.copyOf(checkNotNull(partitions, "partitions is null"));
        }

        public TupleDomain getTupleDomainInput()
        {
            return tupleDomainInput;
        }

        public List<Partition> getPartitions()
        {
            return partitions;
        }

        @Override
        public int hashCode()
        {
            return Objects.hashCode(tupleDomainInput, partitions);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final GeneratedPartitions other = (GeneratedPartitions) obj;
            return Objects.equal(this.tupleDomainInput, other.tupleDomainInput) && Objects.equal(this.partitions, other.partitions);
        }
    }
}