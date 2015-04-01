package us.kbase.kbasejobservice;

import us.kbase.auth.AuthToken;
import us.kbase.common.service.JsonServerMethod;
import us.kbase.common.service.JsonServerServlet;

//BEGIN_HEADER

import java.io.File;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import us.kbase.common.service.UObject;
import us.kbase.common.utils.UTCDateFormat;
//END_HEADER
import us.kbase.scripts.util.ProcessHelper;

/**
 * <p>Original spec-file module name: KBaseJobService</p>
 * <pre>
 * </pre>
 */
public class KBaseJobServiceServer extends JsonServerServlet {
    private static final long serialVersionUID = 1L;

    //BEGIN_CLASS_HEADER
    private int lastJobId = 0;
    //private Map<String, RunJobParams> jobs = new LinkedHashMap<>();
    private Map<String, FinishJobParams> results = new LinkedHashMap<String, FinishJobParams>();
    private File binDir = null;
    private File tempDir = null;
    
    public KBaseJobServiceServer withBinDir(File binDir) {
        this.binDir = binDir;
        return this;
    }
    public KBaseJobServiceServer withTempDir(File tempDir) {
        this.tempDir = tempDir;
        return this;
    }
    //END_CLASS_HEADER

    public KBaseJobServiceServer() throws Exception {
        super("KBaseJobService");
        //BEGIN_CONSTRUCTOR
        //END_CONSTRUCTOR
    }

    /**
     * <p>Original spec-file function name: run_job</p>
     * <pre>
     * Start a new job
     * </pre>
     * @param   params   instance of type {@link us.kbase.kbasejobservice.RunJobParams RunJobParams}
     * @return   parameter "job_id" of original type "job_id" (A job id.)
     */
    @JsonServerMethod(rpc = "KBaseJobService.run_job")
    public String runJob(RunJobParams params, AuthToken authPart) throws Exception {
        String returnVal = null;
        //BEGIN run_job
        lastJobId++;
        final String jobId = "" + lastJobId;
        final String token = authPart.toString();
        returnVal = jobId;
        final File jobDir = new File(tempDir, "job_" + jobId);
        if (!jobDir.exists())
            jobDir.mkdirs();
        File jobFile = new File(jobDir, "job.json");
        UObject.getMapper().writeValue(jobFile, params);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RunJobParams job = getJobParams(jobId, new AuthToken(token));
                    Context context = job.getContext();
                    if (context == null)
                        context = new Context();
                    if (context.getCallStack() == null)
                        context.setCallStack(new ArrayList<MethodCall>());
                    context.getCallStack().add(new MethodCall().withJobId(jobId)
                            .withService(job.getService()).withServiceVer(job.getServiceVer())
                            .withMethod(job.getMethod()).withMethodParams(job.getMethodParams())
                            .withTime(new UTCDateFormat().formatDate(new Date())));
                    File jobDir = new File(tempDir, "job_" + jobId);
                    if (!jobDir.exists())
                        jobDir.mkdirs();
                    File contextFile = new File(jobDir, "context.json");
                    UObject.getMapper().writeValue(contextFile, context);
                    File scriptFile = new File(binDir, "run_" + job.getService() + "_async_job.sh");
                    File resultFile = new File(jobDir, "result.json");
                    ProcessHelper.cmd("bash", scriptFile.getCanonicalPath(), contextFile.getCanonicalPath(),
                            resultFile.getCanonicalPath(), token).exec(jobDir);
                    FinishJobParams result = UObject.getMapper().readValue(resultFile, FinishJobParams.class);
                    finishJob(jobId, result, new AuthToken(token));
                } catch (Exception ex) {
                    FinishJobParams result = new FinishJobParams().withError(new JsonRpcError().withCode(-1L)
                            .withName("JSONRPCError").withMessage("Job service side error: " + ex.getMessage()));
                    try {
                        finishJob(jobId, result, new AuthToken(token));
                    } catch (Exception ignore) {}
                }
            }
        }).start();
        //END run_job
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_job_params</p>
     * <pre>
     * Get job params necessary for job execution
     * </pre>
     * @param   jobId   instance of original type "job_id" (A job id.)
     * @return   parameter "params" of type {@link us.kbase.kbasejobservice.RunJobParams RunJobParams}
     */
    @JsonServerMethod(rpc = "KBaseJobService.get_job_params")
    public RunJobParams getJobParams(String jobId, AuthToken authPart) throws Exception {
        RunJobParams returnVal = null;
        //BEGIN get_job_params
        final File jobDir = new File(tempDir, "job_" + jobId);
        if (!jobDir.exists())
            jobDir.mkdirs();
        File jobFile = new File(jobDir, "job.json");
        returnVal = UObject.getMapper().readValue(jobFile, RunJobParams.class);
        //END get_job_params
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: finish_job</p>
     * <pre>
     * Register results of already started job
     * </pre>
     * @param   jobId   instance of original type "job_id" (A job id.)
     * @param   params   instance of type {@link us.kbase.kbasejobservice.FinishJobParams FinishJobParams}
     */
    @JsonServerMethod(rpc = "KBaseJobService.finish_job")
    public void finishJob(String jobId, FinishJobParams params, AuthToken authPart) throws Exception {
        //BEGIN finish_job
        results.put(jobId, params);
        //END finish_job
    }

    /**
     * <p>Original spec-file function name: check_job</p>
     * <pre>
     * Check if job is finished and get results/error
     * </pre>
     * @param   jobId   instance of original type "job_id" (A job id.)
     * @return   parameter "job_state" of type {@link us.kbase.kbasejobservice.JobState JobState}
     */
    @JsonServerMethod(rpc = "KBaseJobService.check_job")
    public JobState checkJob(String jobId, AuthToken authPart) throws Exception {
        JobState returnVal = null;
        //BEGIN check_job
        returnVal = new JobState();
        FinishJobParams result = results.get(jobId);
        if (result == null) {
            returnVal.setFinished(0L);
        } else {
            returnVal.setFinished(1L);
            returnVal.setResult(result.getResult());
            returnVal.setError(result.getError());
        }
        //END check_job
        return returnVal;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: <program> <server_port>");
            return;
        }
        new KBaseJobServiceServer().startupServer(Integer.parseInt(args[0]));
    }
}
