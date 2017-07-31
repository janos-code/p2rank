package cz.siret.prank.program.routines

import cz.siret.prank.program.params.ListParam
import cz.siret.prank.program.params.Params
import cz.siret.prank.program.params.optimizer.HObjectiveFunction
import cz.siret.prank.program.params.optimizer.HOptimizer
import cz.siret.prank.program.params.optimizer.HVariable
import cz.siret.prank.program.params.optimizer.spearmint.HSpearmintOptimizer
import cz.siret.prank.program.routines.results.EvalResults
import cz.siret.prank.utils.Futils
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import cz.siret.prank.program.routines.ParamLooper.ParamVal
import cz.siret.prank.program.routines.ParamLooper.Step
import groovy.util.logging.Slf4j

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Hyperparameter optimizer routine
 */
@Slf4j
@CompileStatic
class HyperOptimizer extends ParamLooper {

    List<ListParam> listParams

    HyperOptimizer(String outdir, List<ListParam> listParams) {
        super(outdir)
        this.listParams = listParams
    }

    @CompileDynamic
    private HVariable convertListParam(ListParam p) {
        assert p.values.size() == 2

        String name = p.name

        HVariable.Type type = HVariable.Type.FLOAT
        if (Params.inst."$name" instanceof Integer) {
            type = HVariable.Type.INT
        }

        Number min = Double.parseDouble p.values[0].toString()
        Number max = Double.parseDouble p.values[1].toString()


        new HVariable(name, type, min, max)
    }


    private List<HVariable> convertListParams(List<ListParam> listParams) {
        listParams.collect { convertListParam(it) }.toList()
    }

    public void optimizeParameters(Closure<EvalResults> evalClosure) {

        log.info "List variables: " + listParams.toListString()
        List<HVariable> variables = convertListParams(listParams)
        log.info "Variables: " + variables.toListString()

        HOptimizer optimizer = createOptimizer(variables)

        optimizer.optimize(new HObjectiveFunction() {
            @Override
            double eval(Map<String, Object> variableValues, int stepNumber) {

                List<ParamVal> paramVals = variables.collect { new ParamVal(name: it.name, value: variableValues.get(it.name)) }.toList()
                Step step = new Step(params: paramVals)
                steps.add(step)

                double  val

                try {
                    EvalResults res = processStep(step, "step.$stepNumber", evalClosure)
                    val = getObjectiveValue(res)
                } catch (Exception e) {
                    log.error("Couldn't process grid optimization step $stepNumber", e)
                    val = Double.NaN
                }

                return val
            }
        })

    }

    double getObjectiveValue(EvalResults res) {
        String name = params.hopt_objective
        double sign = 1
        if (name.startsWith("-")) {
            name = name.substring(1)
            sign = -1
        }
        return sign * (double)res.stats.get(name)
    }

    private HOptimizer createOptimizer(List<HVariable> variables) {
        // TODO: other optimizers

        Path spearmintDir = Paths.get( Futils.absSafePath(params.hopt_spearmint_dir) )
        Path expeimentDir = Paths.get( Futils.absSafePath("$outdir/spearmint") )

        HOptimizer optimizer = new HSpearmintOptimizer(spearmintDir, expeimentDir)
        optimizer.withMaxIterations(params.hopt_max_iterations)
        optimizer.withVariables(variables)
        return optimizer
    }

}
