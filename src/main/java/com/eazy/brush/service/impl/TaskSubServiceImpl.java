package com.eazy.brush.service.impl;

import com.eazy.brush.controller.view.service.Operator;
import com.eazy.brush.controller.view.service.RandomMacAddress;
import com.eazy.brush.core.enums.CountType;
import com.eazy.brush.core.enums.TaskSpeedType;
import com.eazy.brush.core.lottery.Award;
import com.eazy.brush.core.lottery.LotteryUtil;
import com.eazy.brush.core.utils.Constants;
import com.eazy.brush.core.utils.DateTimeUitl;
import com.eazy.brush.core.utils.RandomUtil;
import com.eazy.brush.dao.entity.*;
import com.eazy.brush.dao.mapper.TaskSubMapper;
import com.eazy.brush.service.*;
import com.eazy.brush.service.rank.HcountService;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 元任务相关服务
 * author : liufeng
 * create time:2016/8/28 13:02
 */
@Slf4j
@Service
public class TaskSubServiceImpl implements TaskSubService {

    private Random random = new Random();

    @Autowired
    private TaskSubMapper taskSubMapper;

    @Autowired
    private TaskActionService taskActionService;

    @Autowired
    private DeviceInfoService deviceInfoService;

    @Autowired
    private HcountService hcountService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProxyIpService proxyIpService;

    class NetType<T> implements Award {

        private T netType;
        private double rate;

        public NetType(T netType, double rate) {
            this.netType = netType;
            this.rate = rate;
        }

        public T getNetType() {
            return netType;
        }

        @Override
        public double getRate() {
            return rate;
        }
    }

    @Override
    public List<TaskSub> getUnConsumeList(long pertime, int size) {
        return taskSubMapper.getList(pertime, size);
    }

    @Override
    public void makeIncrDayTaskSub(Task task) {

        List<ActionPage> actionPageList = taskActionService.getActionsByTaskId(task.getId());
        List<DeviceInfo> deviceInfos = deviceInfoService.getList(0, Integer.MAX_VALUE);

        int perNum = 0, times = 0;

        if (TaskSpeedType.make_immediate.getCode() == task.getRunSpeed()) {     //立即投放
            times = task.getIncrDay() / Constants.TASK_BATCH_UP + 1;            //需要分多少批次执行
            perNum = times > 0 ? Constants.TASK_BATCH_UP : task.getIncrDay();
        } else {                                                                //函数投放
            times = (task.getRunEndTime() - task.getRunStartTime()) * 60 / Constants.TASK_SUB_PER_MINITE;
            perNum = task.getIncrDay() / times + 1;                             //不能整除，所以多运行几个
        }

        DateTime nowDateTime = DateTime.now();
        DateTime createDateTime = new DateTime(task.getCreateTime());
        int interDay = DateTimeUitl.getDayInter(createDateTime, nowDateTime);
        DateTime startTime = DateTimeUitl.getStartTime(createDateTime, task.getRunStartTime(), interDay);

        if (interDay == 0) {                                                    //如果是当天，从当前时间之后算起
            times = times - DateTimeUitl.perTimeNum(task.getRunStartTime(), nowDateTime);
        }

        int i = times;
        while (i-- > 0) {
            long perTime = Long.parseLong(startTime.toString("yyyyMMddHHmm"));
            if (perNum > 0) {
                buildTaskSubs(task, perTime, actionPageList, deviceInfos, perNum);
            }
            startTime = startTime.plusMinutes(Constants.TASK_SUB_PER_MINITE);
        }
        log.info("### taskId:{},taskNum:{} make finished! ###", task.getId(), task.getIncrDay());

        int createDay = Integer.parseInt(DateTime.now().toString("yyyyMMdd"));

        //删除多生成的任务
        int size = times * perNum - task.getIncrDay();
        if (size > 0) {
            taskSubMapper.deleteRand(task.getId(), createDay, size);
        }

        //计数
        hcountService.incrBy(task.getId(), createDay, CountType.taskSubDayNum, task.getIncrDay());
    }

    @Override
    public void makeRetainDayTaskSub(Task task) {

        DateTime curDateTime = new DateTime(task.getCreateTime());
        int inderDay = DateTimeUitl.getDayInter(curDateTime, DateTime.now());
        int createDay = Integer.parseInt(DateTime.now().toString("yyyyMMdd"));
        int totalNum = 0;

        //从任务开始到今日
        for (int i = 0; i < inderDay && i < task.getRetainDay(); i++) {
            curDateTime = curDateTime.plusDays(i);
            int retainNum = taskService.calcDayRetainNum(task, DateTime.now());
            totalNum = totalNum + retainNum;

            int count = 0, size = 100;
            DateTime start = DateTime.now().withHourOfDay(task.getRunStartTime()).withMinuteOfHour(0).withSecondOfMinute(0);
            DateTime end = DateTime.now().withHourOfDay(task.getRunEndTime()).withMinuteOfHour(0).withSecondOfMinute(0);

            int rDay = Integer.parseInt(curDateTime.toString("yyyyMMdd"));
            List<TaskSub> randList = taskSubMapper.getRandList(rDay, size);

            int times = retainNum / size;
            int t = 0;
            while (!CollectionUtils.isEmpty(randList)) {
                if (t > times - 2) {
                    break;
                }
                makeRetain(randList, start, end);
                t++;
                count += size;
                randList = taskSubMapper.getRandList(rDay, size);
                log.info("### make TaskSub retain taskid {},day {},size {},count {} ###", task.getId(), rDay, size, count);
            }
            //最后一次循环少运行的，补充
            size = retainNum - (t * size);
            randList = taskSubMapper.getRandList(rDay, size);
            makeRetain(randList, start, end);
            log.info("### make last TaskSub retain taskid {},day {},size {} ###", task.getId(), rDay, size);

            //删除未被留存的taskSub
            int num = taskSubMapper.deleteUnRetain(rDay);
            log.info("### deleteUnRetain taskSub taskid {},day {},num {} ###", task.getId(), rDay, num);
        }
        //计数
        hcountService.incrBy(task.getId(), createDay, CountType.taskSubDayNum, totalNum);
    }

    private void makeRetain(List<TaskSub> randList, DateTime start, DateTime end) {
        for (TaskSub taskSub : randList) {
            taskSub.setCallbackTime(0);
            taskSub.setPerTime(DateTimeUitl.getRandomPerTime(start, end));
            taskSubMapper.makeRetain(taskSub);
        }
    }

    @Override
    public void insertTaskSub(TaskSub taskSub) {
        taskSubMapper.insertTaskSub(taskSub);
    }

    @Override
    public void insertTaskBatch(List<TaskSub> taskSubList) {
        if (!CollectionUtils.isEmpty(taskSubList)) {
            taskSubMapper.insertTaskSubBatch(taskSubList);
        }
    }

    @Override
    public void changeTaskSubState(String ids, long callbackTime) {
        taskSubMapper.changeTaskSubState(ids, callbackTime);
    }

    @Override
    public List<TaskSub> getRandList(int createDay, int size) {
        return taskSubMapper.getRandList(createDay, size);
    }

    @Override
    public int count(int taskId, int createDay) {
        return 0;
    }

    /**
     * 生成任务元
     *
     * @param task
     * @param actionPageList
     * @param deviceInfos
     * @param taskNum
     */
    private void buildTaskSubs(Task task, long perTime, List<ActionPage> actionPageList,
                               List<DeviceInfo> deviceInfos, int taskNum) {


        List<TaskSub> taskSubs = Lists.newArrayList();
        for (int num = 0; num < taskNum; num++) {

            TaskSub taskSub = new TaskSub();
            taskSub.setId(UUID.randomUUID().toString());
            taskSub.setTaskId(task.getId());
            taskSub.setPerTime(perTime);
            taskSub.setDeviceInfoId(LotteryUtil.lottery(deviceInfos).getId());
            taskSub.setRunTime(task.getRunTime());
            taskSub.setCreateDay(Integer.parseInt(DateTime.now().toString("yyyyMMdd")));
            setCardInfo(taskSub);
            setNetInfo(taskSub);
            taskSub.setVersionIncremental(RandomUtil.generateMixString(13));
            taskSub.setBuildId(RandomUtil.generateMixString(7));
            taskSub.setSecureId(UUID.randomUUID().toString());
            taskSub.setSerial(RandomUtil.generateMixString(16));
            taskSubs.add(taskSub);
        }
        insertTaskBatch(taskSubs);
    }

    private void setCardInfo(TaskSub taskSub) {
        List<NetType<String>> netTypes = Lists.newArrayList();
        netTypes.add(new NetType<String>("2g", 301));
        netTypes.add(new NetType<String>("3g", 914));
        netTypes.add(new NetType<String>("4g", 9444));

        Operator[] operators = Operator.getInstances((String) LotteryUtil.lottery(netTypes).getNetType());
        Operator operator = LotteryUtil.lottery(Arrays.asList(operators));
        CardInfo cardInfo = operator.getCardInfo();

        taskSub.setTelAndroidId(cardInfo.getTelAndroidId());       //android_id 唯一
        taskSub.setSubscriberId(cardInfo.getSubscriberId());       //跟operator有关系，前5位时operator
        taskSub.setOperator(cardInfo.getOperator());           //运营商标志码
        taskSub.setOperatorName(cardInfo.getOperatorName());       //中国联通\\中国电信\\中国移动
        taskSub.setLine1Number(cardInfo.getLine1Number());        //联通手机的手机号码
        taskSub.setSimSerialNumber(cardInfo.getSimSerialNumber());    //sim卡串号
        taskSub.setNetworkType(cardInfo.getNetworkType());        //手机卡网络类型
        taskSub.setPhoneType(cardInfo.getPhoneType());            //手机通话类型
    }

    private void setNetInfo(TaskSub taskSub) {
        NetInfo netInfo = new NetInfo();
        netInfo.setMac(RandomMacAddress.getMacAddrWithFormat(":"));

        List<NetType<Integer>> netInfoTypes = Lists.newArrayList();
        netInfoTypes.add(new NetType<>(0, 56937));
        netInfoTypes.add(new NetType<>(1, 10000));
        netInfo.setType(LotteryUtil.lottery(netInfoTypes).getNetType());
        taskSub.setMac(netInfo.getMac());                //mac地址 唯一
        taskSub.setType(netInfo.getType());                  //网络类型 0 手机网络 1 wifi
    }
}
