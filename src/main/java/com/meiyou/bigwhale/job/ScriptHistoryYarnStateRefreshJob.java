package com.meiyou.bigwhale.job;

import com.meiyou.bigwhale.common.Constant;
import com.meiyou.bigwhale.common.pojo.HttpYarnApp;
import com.meiyou.bigwhale.dto.DtoScript;
import com.meiyou.bigwhale.dto.DtoScriptHistory;
import com.meiyou.bigwhale.entity.Cluster;
import com.meiyou.bigwhale.entity.Script;
import com.meiyou.bigwhale.entity.ScriptHistory;
import com.meiyou.bigwhale.service.ClusterService;
import com.meiyou.bigwhale.service.ScriptService;
import com.meiyou.bigwhale.util.YarnApiUtils;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

@DisallowConcurrentExecution
public class ScriptHistoryYarnStateRefreshJob extends AbstractRetryableJob implements InterruptableJob {

    private Thread thread;
    private volatile boolean interrupted = false;

    @Autowired
    private ClusterService clusterService;
    @Autowired
    private ScriptService scriptService;

    @Override
    public void interrupt() {
        if (!interrupted) {
            interrupted = true;
            thread.interrupt();
        }
    }

    @Override
    public void execute(JobExecutionContext jobExecutionContext) {
        thread = Thread.currentThread();
        // 遍历集群
        Iterable<Cluster> clusters = clusterService.findAll();
        for (Cluster cluster : clusters) {
            List<Script> scripts = scriptService.findByQuery("clusterId=" + cluster.getId());
            if (scripts.isEmpty()) {
                continue;
            }
            List<ScriptHistory> scriptHistories = scriptHistoryService.findByQuery("clusterId=" + cluster.getId() + ";jobFinalStatus=UNDEFINED");
            if (scriptHistories.isEmpty()) {
                continue;
            }
            // 请求yarn web url, 获取所有应用
            List<HttpYarnApp> httpYarnApps = YarnApiUtils.getActiveApps(cluster.getYarnUrl());
            // 请求出错，不清理数据
            if (httpYarnApps == null) {
                continue;
            }
            httpYarnApps.removeIf(httpYarnApp -> !httpYarnApp.getName().contains(".bw_instance_") && !httpYarnApp.getName().contains(".bw_test_instance_"));
            Map<Integer, Script> scriptId2ObjMap = new HashMap<>();
            scripts.forEach(script -> scriptId2ObjMap.put(script.getId(), script));
            Map<String, ScriptHistory> scriptUserAndQueueAndName2ScriptHistoryMap = new HashMap<>();
            scriptHistories.forEach(scriptHistory -> {
                String user;
                if (scriptHistory.getScriptId() != null) {
                    Script script = scriptId2ObjMap.get(scriptHistory.getScriptId());
                    user = script != null ? script.getUser() : String.valueOf(scriptHistory.getScriptId());
                } else {
                    user = DtoScriptHistory.extractUser(scriptHistory.getOutputs());
                }
                String [] arr = DtoScript.extractQueueAndApp(scriptHistory.getScriptType(), scriptHistory.getContent());
                String queue = arr[0];
                if (queue != null && !"root".equals(queue) && !queue.startsWith("root.")) {
                    queue = "root." + queue;
                }
                String app = arr[1];
                String key = user + "$" + queue + "$" + app;
                scriptUserAndQueueAndName2ScriptHistoryMap.put(key, scriptHistory);
            });
            Set<Integer> matchIds = new HashSet<>();
            for (HttpYarnApp httpYarnApp : httpYarnApps) {
                String key = httpYarnApp.getUser() + "$" + httpYarnApp.getQueue() + "$" + httpYarnApp.getName();
                if (!scriptUserAndQueueAndName2ScriptHistoryMap.containsKey(key)) {
                    continue;
                }
                ScriptHistory scriptHistory = scriptUserAndQueueAndName2ScriptHistoryMap.get(key);
                if (key.contains(".bw_test_instance_")) {
                    YarnApiUtils.killApp(cluster.getYarnUrl(), httpYarnApp.getId());
                    scriptHistory.updateState(Constant.JobState.SUCCEEDED);
                    scriptHistory.setJobFinalStatus(Constant.JobState.KILLED);
                    scriptHistory.setFinishTime(new Date());
                    scriptHistoryService.save(scriptHistory);
                } else {
                    updateMatchScriptHistory(httpYarnApp, scriptHistory);
                }
                matchIds.add(scriptHistory.getId());
            }
            scriptHistories.forEach(scriptHistory -> {
                if (!matchIds.contains(scriptHistory.getId())) {
                    updateNoMatchScriptHistory(cluster.getYarnUrl(), scriptHistory, scriptUserAndQueueAndName2ScriptHistoryMap);
                }
            });
        }
    }

    private void updateMatchScriptHistory(HttpYarnApp httpYarnApp, ScriptHistory scriptHistory) {
        if ("RUNNING".equals(httpYarnApp.getState()) ||
                "FINAL_SAVING".equals(httpYarnApp.getState()) ||
                "FINISHING".equals(httpYarnApp.getState()) ||
                "KILLING".equals(httpYarnApp.getState())) {
            scriptHistory.updateState(Constant.JobState.ACCEPTED);
            scriptHistory.updateState(Constant.JobState.RUNNING);
        } else {
            scriptHistory.updateState(httpYarnApp.getState());
        }
        scriptHistory.setJobId(httpYarnApp.getId());
        scriptHistory.setJobUrl(httpYarnApp.getTrackingUrl());
        scriptHistory.setJobFinalStatus(httpYarnApp.getFinalStatus());
        scriptHistory.setStartTime(new Date(httpYarnApp.getStartedTime()));
        scriptHistoryService.save(scriptHistory);
    }

    private void updateNoMatchScriptHistory(String yarnUrl, ScriptHistory scriptHistory, Map<String, ScriptHistory> scriptUserAndQueueAndName2ScriptHistoryMap) {
        for (Map.Entry<String, ScriptHistory> entry : scriptUserAndQueueAndName2ScriptHistoryMap.entrySet()) {
            if (scriptHistory.getId().equals(entry.getValue().getId())) {
                String [] arr = entry.getKey().split("\\$");
                HttpYarnApp httpYarnApp = YarnApiUtils.getLastNoActiveApp(yarnUrl, arr[0], arr[1], arr[2], 3);
                if (httpYarnApp != null) {
                    if ("FINISHED".equals(httpYarnApp.getState())) {
                        scriptHistory.updateState(httpYarnApp.getFinalStatus());
                    } else {
                        scriptHistory.updateState(httpYarnApp.getState());
                    }
                    scriptHistory.setJobId(httpYarnApp.getId());
                    scriptHistory.setJobUrl(httpYarnApp.getTrackingUrl());
                    scriptHistory.setJobFinalStatus(httpYarnApp.getFinalStatus());
                    if ("FAILED".equals(httpYarnApp.getFinalStatus())) {
                        if (httpYarnApp.getDiagnostics() != null) {
                            if (httpYarnApp.getDiagnostics().length() > 61440) {
                                scriptHistory.setErrors(httpYarnApp.getDiagnostics().substring(0, 61440));
                            } else {
                                scriptHistory.setErrors(httpYarnApp.getDiagnostics());
                            }
                        }
                    }
                    scriptHistory.setStartTime(new Date(httpYarnApp.getStartedTime()));
                    scriptHistory.setFinishTime(new Date(httpYarnApp.getFinishedTime()));
                } else {
                    scriptHistory.updateState(Constant.JobState.FAILED);
                    scriptHistory.setJobFinalStatus("UNKNOWN");
                    scriptHistory.setFinishTime(new Date());
                }
                scriptHistoryService.save(scriptHistory);
                if (!"SUCCEEDED".equals(scriptHistory.getJobFinalStatus())) {
                    if (Constant.ScriptType.SPARK_BATCH.equals(scriptHistory.getScriptType())) {
                        retryCurrentNode(scriptHistory, String.format(Constant.ErrorType.SPARK_BATCH_UNUSUAL, scriptHistory.getJobFinalStatus()));
                    } else if ( Constant.ScriptType.FLINK_BATCH.equals(scriptHistory.getScriptType())) {
                        retryCurrentNode(scriptHistory, String.format(Constant.ErrorType.FLINK_BATCH_UNUSUAL, scriptHistory.getJobFinalStatus()));
                    }
                }
                break;
            }
        }
    }

}
