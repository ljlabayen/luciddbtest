/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2006-2006 The Eigenbase Project
// Copyright (C) 2006-2006 Disruptive Tech
// Copyright (C) 2006-2006 LucidEra, Inc.
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package net.sf.farrago.defimpl;

import net.sf.farrago.session.*;
import net.sf.farrago.query.*;
import net.sf.farrago.fem.config.*;

import org.eigenbase.relopt.*;
import org.eigenbase.relopt.hep.*;
import org.eigenbase.rel.*;
import org.eigenbase.rel.rules.*;
import org.eigenbase.oj.rel.*;
import org.eigenbase.rel.convert.*;

import com.disruptivetech.farrago.rel.*;

import java.util.*;

/**
 * FarragoDefaultHeuristicPlanner implements {@link FarragoSessionPlanner}
 * in terms of {@link HepPlanner} with a Farrago-specific rule program.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class FarragoDefaultHeuristicPlanner
    extends HepPlanner
    implements FarragoSessionPlanner
{
    private final FarragoSessionPreparingStmt stmt;
    private final Collection<RelOptRule> medPluginRules;
    private boolean inPluginRegistration;

    static FarragoDefaultHeuristicPlanner newInstance(
        FarragoSessionPreparingStmt stmt)
    {
        Collection<RelOptRule> medPluginRules = new LinkedHashSet<RelOptRule>();
        
        final boolean fennelEnabled = stmt.getRepos().isFennelEnabled();
        final CalcVirtualMachine calcVM =
            stmt.getRepos().getCurrentConfig().getCalcVirtualMachine();
        
        HepProgram program = createHepProgram(
            fennelEnabled,
            calcVM,
            medPluginRules);
        
        FarragoDefaultHeuristicPlanner planner =
            new FarragoDefaultHeuristicPlanner(
                program, stmt, medPluginRules);
        FarragoDefaultPlanner.addStandardRules(planner, fennelEnabled, calcVM);
        return planner;
    }
        
    private FarragoDefaultHeuristicPlanner(
        HepProgram program,
        FarragoSessionPreparingStmt stmt,
        Collection<RelOptRule> medPluginRules)
    {
        super(program);
        this.stmt = stmt;
        this.medPluginRules = medPluginRules;
    }

    // implement FarragoSessionPlanner
    public FarragoSessionPreparingStmt getPreparingStmt()
    {
        return stmt;
    }

    // implement FarragoSessionPlanner
    public void beginMedPluginRegistration(String serverClassName)
    {
        inPluginRegistration = true;
    }

    // implement FarragoSessionPlanner
    public void endMedPluginRegistration()
    {
        inPluginRegistration = false;
    }
        
    // implement RelOptPlanner
    public JavaRelImplementor getJavaRelImplementor(RelNode rel)
    {
        return stmt.getRelImplementor(
            rel.getCluster().getRexBuilder());
    }
        
    // implement RelOptPlanner
    public boolean addRule(RelOptRule rule)
    {
        if (inPluginRegistration) {
            medPluginRules.add(rule);
        }
        return super.addRule(rule);
    }
    
    private static HepProgram createHepProgram(
        boolean fennelEnabled,
        CalcVirtualMachine calcVM,
        Collection<RelOptRule> medPluginRules)
    {
        HepProgramBuilder builder = new HepProgramBuilder();

        // The very first step is to implement index joins on catalog
        // tables.  The reason we do this here is so that we don't
        // disturb the carefully hand-coded joins in the catalog views.
        // TODO:  loosen up
        builder.addRuleByDescription("MedMdrJoinRule");

        // Eliminate AGG(DISTINCT x) now, because this transformation
        // may introduce new joins which need to be optimized further on.
        builder.addRuleInstance(RemoveDistinctAggregateRule.instance);
        
        // Now, pull join conditions out of joins, leaving behind Cartesian
        // products.  Why?  Because PushFilterRule doesn't start from
        // join conditions, only filters.  It will push them right back
        // into and possibly through the join.
        builder.addRuleInstance(ExtractJoinFilterRule.instance);

        // Remove trivial projects so tables referenced in selects in the
        // from clause can be optimized with the rest of the query
        builder.addRuleInstance(new RemoveTrivialProjectRule());

        // Push filters down past joins.
        builder.addRuleInstance(new PushFilterRule());

        // This rule will also get run as part of medPluginRules, but
        // we need to do it now before pushing down projections, otherwise
        // a projection can get in the way of a filter.
        builder.addRuleByDescription("FtrsScanToSearchRule");

        // Use index joins in preference to hash joins.  This rule will
        // also get run as part of medPluginRules, but we want to preempt
        // that to make sure some of the other FTRS rules don't fire
        // first, preventing usage of the join.
        builder.addRuleByDescription("FtrsIndexJoinRule");

        // Push projections down.  Do this after index joins, because
        // index joins don't like projections underneath the join.
        builder.addGroupBegin();
        builder.addRuleInstance(new RemoveTrivialProjectRule());
        builder.addRuleInstance(new PushProjectPastJoinRule());
        builder.addRuleInstance(new PushProjectPastFilterRule());
        builder.addGroupEnd();

        // We're getting close to physical implementation.  First, insert
        // type coercions for expressions which require it.
        builder.addRuleClass(CoerceInputsRule.class);

        // Run any SQL/MED plugin rules.  Note that
        // some of these may rely on CoerceInputsRule above.
        builder.addRuleCollection(medPluginRules);
        builder.addRuleInstance(new RemoveTrivialProjectRule());

        // Use hash join where possible. Make sure this rule is called
        // before any physical conversions have been done
        /*
        // TODO jvs 3-May-2006:  Once hash partitioning is available.
        builder.addRuleInstance(new LhxJoinRule());
        */
        
        // Extract join conditions again so that FennelCartesianJoinRule can do
        // its job.  Need to do this before converting filters to calcs, but
        // after other join strategies such as hash join have been attempted,
        // because they rely on the join condition being part of the join.
        builder.addRuleInstance(ExtractJoinFilterRule.instance);

        // Replace AVG with SUM/COUNT (need to do this BEFORE calc conversion
        // and decimal reduction).
        builder.addRuleInstance(ReduceAggregatesRule.instance);
        
        // Handle trivial renames now so that they don't get
        // implemented as calculators.
        if (fennelEnabled) {
            builder.addRuleInstance(new FennelRenameRule());
        }
        
        // Convert remaining filters and projects to logical calculators,
        // merging adjacent ones.  Calculator expressions containing
        // multisets and windowed aggs may yield new projections,
        // so run all these rules together until fixpoint.
        builder.addGroupBegin();
        builder.addRuleInstance(FilterToCalcRule.instance);
        builder.addRuleInstance(ProjectToCalcRule.instance);
        builder.addRuleInstance(MergeCalcRule.instance);
        builder.addRuleInstance(WindowedAggSplitterRule.instance);
        builder.addRuleInstance(new FarragoMultisetSplitterRule());
        builder.addGroupEnd();

        // These rules handle expressions which can be introduced by multiset
        // rewrite.
        // Eliminate UNION DISTINCT and trivial UNION.
        builder.addRuleClass(CoerceInputsRule.class);
        builder.addRuleInstance(new UnionToDistinctRule());
        builder.addRuleInstance(new UnionEliminatorRule());
        // Eliminate redundant SELECT DISTINCT.
        builder.addRuleInstance(new RemoveDistinctRule());

        // Replace the DECIMAL datatype with primitive ints.
        builder.addRuleInstance(new ReduceDecimalsRule());
        
        // Implement DISTINCT via tree-sort instead of letting it
        // be handled via normal sort plus agg.
        builder.addRuleInstance(new FennelDistinctSortRule());

        // The rest of these are all physical implementation rules
        // which are safe to apply simultaneously.
        builder.addGroupBegin();

        // Implement calls to UDX's.
        builder.addRuleInstance(FarragoJavaUdxRule.instance);

        if (fennelEnabled) {
            builder.addRuleInstance(new FennelSortRule());
            builder.addRuleInstance(new FennelRenameRule());
            builder.addRuleInstance(new FennelCartesianJoinRule());
            builder.addRuleInstance(new FennelAggRule());
            builder.addRuleInstance(new FennelCollectRule());
            builder.addRuleInstance(new FennelUncollectRule());
            builder.addRuleInstance(new FennelCorrelatorRule());
            builder.addRuleInstance(new FennelValuesRule());
        } else {
            builder.addRuleInstance(
                new IterRules.HomogeneousUnionToIteratorRule());
        }

        if (calcVM.equals(CalcVirtualMachineEnum.CALCVM_FENNEL)) {
            // use Fennel for calculating expressions
            assert(fennelEnabled);
            builder.addRuleInstance(FennelCalcRule.instance);
            builder.addRuleInstance(new FennelOneRowRule());
            // NOTE jvs 3-May-2006:  See corresponding REVIEW comment in
            // FarragoDefaultPlanner about why this goes here.
            builder.addRuleInstance(FennelUnionRule.instance);
        } else if (calcVM.equals(CalcVirtualMachineEnum.CALCVM_JAVA)) {
            // use Java code generation for calculating expressions
            builder.addRuleInstance(IterRules.IterCalcRule.instance);
            builder.addRuleInstance(new IterRules.OneRowToIteratorRule());
            builder.addRuleInstance(
                new IterRules.HomogeneousUnionToIteratorRule());
        }

        // Finish main physical implementation group.
        builder.addGroupEnd();

        // If automatic calculator selection is enabled (the default),
        // figure out what to do with CalcRels.
        if (calcVM.equals(CalcVirtualMachineEnum.CALCVM_AUTO)) {
            // First, attempt to choose calculators such that converters are
            // minimized
            builder.addConverters(false);
            // Split remaining expressions into Fennel part and Java part
            builder.addRuleInstance(FarragoAutoCalcRule.instance);
            // Convert expressions, giving preference to Java
            builder.addRuleInstance(new IterRules.OneRowToIteratorRule());
            builder.addRuleInstance(IterRules.IterCalcRule.instance);
            builder.addRuleInstance(FennelCalcRule.instance);
            // Use Fennel for unions.
            builder.addRuleInstance(FennelUnionRule.instance);
        }
        
        // Finally, add generic converters as necessary.
        builder.addConverters(true);
        
        return builder.createProgram();
    }
}

// End FarragoDefaultHeuristicPlanner.java