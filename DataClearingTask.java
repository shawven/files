package com.yunzhijia.workassistant.scheduler;

import com.google.common.collect.Lists;
import com.yunzhijia.workassistant.common.LoggerApi;
import com.yunzhijia.workassistant.common.constant.CommonConstants;
import com.yunzhijia.workassistant.common.redis.RedisTemp;
import com.yunzhijia.workassistant.common.redis.lock.GlobalTryLock;
import com.yunzhijia.workassistant.common.utils.Times;
import com.yunzhijia.workassistant.dao.ICooperationNewWorkDao;
import com.yunzhijia.workassistant.service.local.IThirdNewWorkLocalService;
import org.bson.types.ObjectId;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;

/**
 * @author kingdee
 */
@Component
public class DataClearingTask extends LoggerApi {

    @Autowired
    private IThirdNewWorkLocalService thirdNewWorkLocalService;

    @Autowired
    private ICooperationNewWorkDao cooperationNewWorkDao;

    /**
     * 定时任务早上2点跑<br/>
     * 删除两周前的第三方日程
     */
    @GlobalTryLock(timeToExclusive = 600)
    @Scheduled(cron = "0 0 2 * * ?")
    public void deleteThirdNewWorkTask() {
        MDC.put(CommonConstants.LOG_TRACE_ID, ObjectId.get().toString());
        info("deleteThirdNewWorkTask start");

        // 无用的第三方日程数据暂时只保留两周
        int weeks = 2;
        // 定期清理的数据类型,生日祝福和工作汇报提醒
        ArrayList<String> channels = Lists.newArrayList("birthday", "workReport", "award");
//        thirdNewWorkLocalService.deleteOtherChannelNewWork(channels, weeks);

        info("deleteThirdNewWorkTask end");
    }

    /**
     * 定时任务每个月的第一个星期的星期一（表达式中对应为每月1-7号的星期一）
     * 删除上月最后一周以前的头像（卡片在跨越时，最多查询到上月的最后一周）
     */
    @GlobalTryLock(timeToExclusive = 600)
    @Scheduled(cron = "0 0 0 1-7 * 1")
    public void deleteCooperationNew() {
        MDC.put(CommonConstants.LOG_TRACE_ID, ObjectId.get().toString());
        info("deleteCooperationNew start");

        // 在此时间点（第一个星期的星期一）清除本月以前的数据
        long monthOfStart = Times.toMillis(YearMonth.now().atDay(1).atStartOfDay());
        cooperationNewWorkDao.clear(monthOfStart - 1);

        info("deleteCooperationNew end");
    }
}
