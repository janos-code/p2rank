package cz.siret.prank.program.routines

import cz.siret.prank.domain.Dataset
import cz.siret.prank.domain.DatasetCachedLoader
import cz.siret.prank.program.Main
import cz.siret.prank.program.params.ListParam
import cz.siret.prank.program.params.Params
import cz.siret.prank.program.params.optimizer.HVariable
import cz.siret.prank.program.routines.results.EvalResults
import cz.siret.prank.utils.CmdLineArgs
import cz.siret.prank.utils.Futils
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j

import static cz.siret.prank.utils.ThreadUtils.async

/**
 * ploop and traineval routines for oprimization experiments
 */
@Slf4j
class Experiments extends Routine {

    String command

    Dataset trainDataset
    Dataset evalDataset
    boolean doCrossValidation = false

    String trainSetFile
    String evalSetFile
    String outdirRoot
    String datadirRoot

    String label

    CmdLineArgs cmdLineArgs

    public Experiments(CmdLineArgs args, Main main, String command) {
        super(null)
        this.cmdLineArgs = args
        this.command = command

        trainSetFile =  cmdLineArgs.get('train', 't')
        trainSetFile = Main.findDataset(trainSetFile)
        trainDataset = DatasetCachedLoader.loadDataset(trainSetFile)

        // TODO: enable executing 'prank ploop crossval'
        // (now ploop with crossvalidation is possible only implicitly by not specifying eval dataset)

        evalSetFile  =  cmdLineArgs.get('eval', 'e')
        if (evalSetFile!=null) { // no eval dataset -> do crossvalidation
            evalSetFile = Main.findDataset(evalSetFile)
            evalDataset = DatasetCachedLoader.loadDataset(evalSetFile)
        } else {
            doCrossValidation = true
        }

        outdirRoot = params.output_base_dir
        datadirRoot = params.dataset_base_dir
        label = command + "_" + trainDataset.label + "_" + (doCrossValidation ? "crossval" : evalDataset.label)
        outdir = main.findOutdir(label)
        main.writeCmdLineArgs(outdir)
        writeParams(outdir)

        main.configureLoggers(outdir)
    }

    void execute() {
        log.info "executing $command()"
        this."$command"()  // dynamic exec method
        log.info "results saved to directory [${Futils.absPath(outdir)}]"
    }

//===========================================================================================================//

    /**
     * train/eval on different datasets for different seeds
     * collecting train vectors only once and training+evaluatng many times
     */
    private static EvalResults doTrainEval(String outdir, Dataset trainData, Dataset evalData) {

        TrainEvalRoutine iter = new TrainEvalRoutine(outdir)
        iter.trainDataSet = trainData
        iter.evalDataSet = evalData
        iter.collectTrainVectors()
        //iter.collectEvalVectors() // for further inspection

        EvalRoutine trainRoutine = new EvalRoutine(outdir) {
            @Override
            EvalResults execute() {
                iter.outdir = getEvalRoutineOutir() // is set to "../seed.xx" by SeedLoop
                iter.trainAndEvalModel()
                return iter.evalRoutine.results
            }
        }

        return new SeedLoop(trainRoutine, outdir).execute()
    }

    /**
     * implements command: 'prank traineval...  '
     */
    public EvalResults traineval() {
        doTrainEval(outdir, trainDataset, evalDataset)
    }

//===========================================================================================================//

    /**
     *  iterative parameter optimization
     */
    public ploop() {

        gridOptimize(ListParam.parseListArgs(cmdLineArgs))
    }

    private void gridOptimize(List<ListParam> rparams) {

        log.info "List variables: " + rparams.toListString()

        String topOutdir = outdir

        GridOprimizer go = new GridOprimizer(topOutdir, rparams)
        go.init()
        go.runGridOptimization { String iterDir ->
            return runExperimentStep(iterDir, trainDataset, evalDataset, doCrossValidation)
        }
    }

    /**
     * run trineval or crosvalidation with current paramenter assignment
     */
    private static EvalResults runExperimentStep(String dir, Dataset trainData, Dataset evalData, boolean doCrossValidation) {
        EvalResults res

        if (doCrossValidation) {
            EvalRoutine routine = new CrossValidation(dir, trainData)
            res = new SeedLoop(routine, dir).execute()
        } else {
            res = doTrainEval(dir, trainData, evalData)
        }

        if (Params.inst.ploop_delete_runs) {
            async { Futils.delete(dir) }
        }

        if (Params.inst.clear_prim_caches) {
            trainData.clearPrimaryCaches()
            evalData?.clearPrimaryCaches()
        } else if (Params.inst.clear_sec_caches) {
            trainData.clearSecondaryCaches()
            evalData?.clearSecondaryCaches()
        }

        return res
    }

//===========================================================================================================//

    /**
     *  hyperparameter optimization
     */
    public hopt() {
        HyperOptimizer ho = new HyperOptimizer(outdir, ListParam.parseListArgs(cmdLineArgs)).init()

        ho.optimizeParameters {  String stepDir ->
            return runExperimentStep(stepDir, trainDataset, evalDataset, doCrossValidation)
        }
    }

//===========================================================================================================//

    /**
     *  print parameters and exit
     */
    public params() {
        write params.toString()
    }

}








