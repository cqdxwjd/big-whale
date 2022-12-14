package com.meiyou.bigwhale.util;

import com.meiyou.bigwhale.common.Constant;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * @author Suxy
 * @date 2019/8/30
 * @description file description
 */
public class SchedulerUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerUtils.class);

    private static Scheduler scheduler;

    static {
        if (SpringContextUtils.getApplicationContext() != null) {
            scheduler = SpringContextUtils.getBean(Scheduler.class);
        } else {
            SchedulerFactory schedulerFactory = new StdSchedulerFactory();
            try {
                scheduler = schedulerFactory.getScheduler();
                scheduler.start();
            } catch (SchedulerException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private SchedulerUtils() {

    }

    public static Scheduler getScheduler() {
        return scheduler;
    }

    public static void scheduleCronJob(Class<? extends Job> jobClass, String cronExpression) {
        scheduleCronJob(jobClass, jobClass.getSimpleName(), cronExpression);
    }

    public static void scheduleCronJob(Class<? extends Job> jobClass, Object name, String cronExpression) {
        scheduleCronJob(jobClass, name, Constant.JobGroup.COMMON, cronExpression);
    }

    public static void scheduleCronJob(Class<? extends Job> jobClass, Object name, String group, String cronExpression) {
        scheduleCronJob(jobClass, name, group, cronExpression, null);
    }

    public static void scheduleCronJob(Class<? extends Job> jobClass, Object name, String group, String cronExpression, JobDataMap jobDataMap) {
        scheduleCronJob(jobClass, name, group, cronExpression, jobDataMap, null, null);
    }

    public static void scheduleCronJob(Class<? extends Job> jobClass, Object name, String group, String cronExpression, JobDataMap jobDataMap, Date startDate, Date endDate) {
        try {
            JobKey jobKey = new JobKey(String.valueOf(name), group);
            if (!scheduler.checkExists(jobKey)) {
                JobBuilder jobBuilder = JobBuilder.newJob(jobClass);
                jobBuilder.withIdentity(jobKey);
                if (jobDataMap != null && !jobDataMap.isEmpty()) {
                    jobBuilder.setJobData(jobDataMap);
                }
                JobDetail jobDetail = jobBuilder.build();
                CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(cronExpression).withMisfireHandlingInstructionDoNothing();
                TriggerBuilder<CronTrigger> triggerBuilder = TriggerBuilder.newTrigger().withSchedule(cronScheduleBuilder);
                if (startDate != null) {
                    triggerBuilder.startAt(startDate);
                } else {
                    triggerBuilder.startNow();
                }
                if (endDate != null) {
                    triggerBuilder.endAt(endDate);
                }
                CronTrigger trigger = triggerBuilder.build();
                scheduler.scheduleJob(jobDetail, trigger);
            }
        } catch (Exception e) {
            LOGGER.error("Submit job error, name=" + name + " and group=" + group, e);
        }
    }

    /**
     * ????????????????????????????????????
     * @param jobClass
     */
    public static void scheduleSimpleJob(Class<? extends Job> jobClass) {
        scheduleSimpleJob(jobClass, jobClass.getSimpleName());
    }

    public static void scheduleSimpleJob(Class<? extends Job> jobClass, Object name) {
        scheduleSimpleJob(jobClass, name, 0, 0);
    }

    /**
     * @param jobClass
     * @param name
     * @param intervalInMilliseconds ????????????
     * @param repeatCount ?????????????????????0?????????????????????
     */
    public static void scheduleSimpleJob(Class<? extends Job> jobClass, Object name, long intervalInMilliseconds, int repeatCount) {
        scheduleSimpleJob(jobClass, name, Constant.JobGroup.COMMON, intervalInMilliseconds, repeatCount);
    }

    public static void scheduleSimpleJob(Class<? extends Job> jobClass, Object name, String group, long intervalInMilliseconds, int repeatCount) {
        scheduleSimpleJob(jobClass, name, group, intervalInMilliseconds, repeatCount, null);
    }

    public static void scheduleSimpleJob(Class<? extends Job> jobClass, Object name, String group, long intervalInMilliseconds, int repeatCount, JobDataMap jobDataMap) {
        scheduleSimpleJob(jobClass, name, group, intervalInMilliseconds, repeatCount, jobDataMap, null, null);
    }

    public static void scheduleSimpleJob(Class<? extends Job> jobClass, Object name, String group, long intervalInMilliseconds, int repeatCount, JobDataMap jobDataMap, Date startDate, Date endDate) {
        try {
            JobKey jobKey = new JobKey(String.valueOf(name), group);
            if (!scheduler.checkExists(jobKey)) {
                JobBuilder jobBuilder = JobBuilder.newJob(jobClass);
                jobBuilder.withIdentity(jobKey);
                if (jobDataMap != null && !jobDataMap.isEmpty()) {
                    jobBuilder.setJobData(jobDataMap);
                }
                JobDetail jobDetail = jobBuilder.build();
                SimpleScheduleBuilder simpleScheduleBuilder = SimpleScheduleBuilder.simpleSchedule();
                simpleScheduleBuilder.withIntervalInMilliseconds(intervalInMilliseconds);
                if (repeatCount >= 0) {
                    simpleScheduleBuilder.withRepeatCount(repeatCount);
                } else {
                    simpleScheduleBuilder.repeatForever();
                }
                TriggerBuilder<SimpleTrigger> triggerBuilder = TriggerBuilder.newTrigger().withSchedule(simpleScheduleBuilder);
                if (startDate != null) {
                    triggerBuilder.startAt(startDate);
                } else {
                    triggerBuilder.startNow();
                }
                if (endDate != null) {
                    triggerBuilder.endAt(endDate);
                }
                SimpleTrigger trigger = triggerBuilder.build();
                scheduler.scheduleJob(jobDetail, trigger);
            }
        } catch (Exception e) {
            LOGGER.error("Submit job error, name=" + name + " and group=" + group, e);
        }
    }

    public static void interrupt(Object name, String group) {
        JobKey jobKey = new JobKey(String.valueOf(name), group);
        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.interrupt(jobKey);
            }
        } catch (SchedulerException e) {
            LOGGER.warn("Interrupt job error, name=" + name + " and group=" + group, e);
        }
    }

    public static void deleteJob(Object name, String group) {
        JobKey jobKey = new JobKey(String.valueOf(name), group);
        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }
        } catch (SchedulerException e) {
            LOGGER.warn("Delete job error, name=" + name + " and group=" + group, e);
        }
    }

    public static void pauseJob(Object name, String group) {
        JobKey jobKey = new JobKey(String.valueOf(name), group);
        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.pauseJob(jobKey);
            }
        } catch (SchedulerException e) {
            LOGGER.warn("Pause job error, name=" + name + " and group=" + group, e);
        }
    }

    public static void resumeJob(Object name, String group) {
        JobKey jobKey = new JobKey(String.valueOf(name), group);
        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.resumeJob(jobKey);
            }
        } catch (SchedulerException e) {
            LOGGER.warn("Resume job error, name=" + name + " and group=" + group, e);
        }
    }

}
