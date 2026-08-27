package dev.quantti.fplassistant.squad;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ojAlgo API the solver is built on, and proves it runs on this JDK.
 *
 * <p>It exists because every later assumption about the library — how a binary variable is
 * declared, how a constraint row is written, what an infeasible model returns — is cheaper to
 * falsify here than inside a half-finished solver.
 */
class OjAlgoSmokeTest {

    @Test
    void picksTheBestTwoOfThreeUnderABudget() {
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        double[] value = {5.0, 3.0, 1.0};
        int[] cost = {6, 5, 4};

        List<Variable> vars = new ArrayList<>();
        for (int i = 0; i < value.length; i++) {
            vars.add(model.newVariable("p" + i).binary().weight(value[i]));
        }

        var size = model.newExpression("size").level(2);
        var budget = model.newExpression("budget").upper(11);
        for (int i = 0; i < vars.size(); i++) {
            size.set(vars.get(i), 1);
            budget.set(vars.get(i), cost[i]);
        }

        Optimisation.Result result = model.maximise();

        assertThat(result.getState()).isEqualTo(Optimisation.State.OPTIMAL);
        // The two best cost 11 exactly, which the budget allows.
        assertThat(result.doubleValue(0)).isEqualTo(1.0);
        assertThat(result.doubleValue(1)).isEqualTo(1.0);
        assertThat(result.doubleValue(2)).isEqualTo(0.0);
    }

    @Test
    void reportsInfeasibleRatherThanReturningSomethingWrong() {
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        Variable only = model.newVariable("only").binary().weight(1.0);
        model.newExpression("two").level(2).set(only, 1);

        Optimisation.Result result = model.maximise();

        assertThat(result.getState().isFeasible()).isFalse();
    }
}
