package com.yunzhijia.workassistant.service.local.impl.metting;

import com.google.common.collect.ImmutableMap;
import com.kingdee.open.user.model.Person;
import com.weibo.api.motan.config.springsupport.annotation.MotanReferer;
import com.yunzhijia.meeting.room.book.service.outer.RoomScheduleRPCService;
import com.yunzhijia.workassistant.common.mapper.MapperHelper;
import com.yunzhijia.workassistant.common.utils.NoticeTimeUtils;
import com.yunzhijia.workassistant.common.utils.PartitionUtils;
import com.yunzhijia.workassistant.common.utils.PersonUtils;
import com.yunzhijia.workassistant.common.utils.ThreadPoolUtils;
import com.yunzhijia.workassistant.dao.impl.CooperationNewWorkDao;
import com.yunzhijia.workassistant.dao.impl.MeetParticipantsDao;
import com.yunzhijia.workassistant.dao.impl.MeetingDao;
import com.yunzhijia.workassistant.domain.CooperationNewWork;
import com.yunzhijia.workassistant.domain.MeetParticipants;
import com.yunzhijia.workassistant.domain.Meeting;
import com.yunzhijia.workassistant.dto.meeting.CreateMeetingDto;
import com.yunzhijia.workassistant.dto.meeting.ModifyMeetingDto;
import com.yunzhijia.workassistant.service.local.helper.BeanHelper;
import com.yunzhijia.workassistant.service.third.UserCoreService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

import static com.yunzhijia.workassistant.common.constant.CommonConstants.WorkSource;
import static com.yunzhijia.workassistant.common.utils.MeetingUtils.*;
import static com.yunzhijia.workassistant.common.utils.MeetingUtils.ignoreAllNotice;
import static java.util.stream.Collectors.toList;

@Service
public class MeetingParticipantService {

    private final Logger logger = LoggerFactory.getLogger(MeetingParticipantService.class);

    @MotanReferer
    private RoomScheduleRPCService roomScheduleRpcService;
    @Autowired
    private UserCoreService userCoreService;
    @Autowired
    private MeetingSearchService meetingSearchService;
    @Autowired
    private MeetingPushService meetingPushService;

    @Autowired
    private CooperationNewWorkDao cooperationNewWorkDao;
    @Autowired
    private MeetParticipantsDao meetParticipantsDao;
    @Autowired
    private MeetingDao meetingDao;

    /**
     * 创建与会人
     *
     * @param dto
     * @param newMeeting
     * @param creator
     * @return
     */
    public List<MeetParticipants> createParticipants(CreateMeetingDto dto, Meeting newMeeting, Person creator) {
        // 准备所需的人员信息
        Map<String, Person> personMap = preparePersonMap(dto.getActors(), dto.getLeaders(), creator);

        // 构建新与会人
        List<MeetParticipants> participants = buildNewParticipantsOfActorsAndLeaders(dto.getActors(),
                dto.getLeaders(), newMeeting, personMap);

        // 构建新创建人，新增日程发起人也默认参与
        MeetParticipants participantOfCreator = buildNewParticipantOfCreator(newMeeting, creator);
        // 首位
        participants.add(0, participantOfCreator);

        // 对与会人 卡片New头像、app弹窗、待办、刷新卡片、同步es缓存、短信提醒等
        processNewParticipants(participants, newMeeting, creator, personMap);

        logger.info("createParticipants size: {}", participants.size());
        return participants;
    }

    /**
     * 创建与会人（更新操作）
     *
     * @param dto
     * @param oldMeeting
     * @param newMeeting
     * @param oldParticipants
     * @param creator
     * @return
     */
    public List<MeetParticipants> createParticipantsForUpdate(ModifyMeetingDto dto, Meeting oldMeeting, Meeting newMeeting,
                                                              List<MeetParticipants> oldParticipants, Person creator) {
        List<String> addActors = dto.getAddActors();
        // 排除存在的
        if (CollectionUtils.isNotEmpty(addActors)) {
            oldParticipants.forEach(old -> {
                addActors.removeIf(oid -> Objects.equals(old.getOid(), oid));
            });
        }

        // 准备所需的人员信息
        Map<String, Person> personMap = preparePersonMap(dto.getAddActors(), dto.getAddLeaders(), creator);
        if (personMap.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建新与会人
        List<MeetParticipants> participants = buildNewParticipantsOfActorsAndLeaders(dto.getAddActors(),
                dto.getAddLeaders(), newMeeting, personMap);

        // 对与会人 卡片New头像、app弹窗、待办、刷新卡片、同步es缓存、短信提醒等
        processNewParticipants(participants, newMeeting, creator, personMap);

        logger.info("createParticipantsForUpdate size: {}", participants.size());
        return participants;
    }

    /**
     * 更新与会人
     *
     * @param dto
     * @param oldMeeting
     * @param oldMeeting
     * @param newMeeting
     * @param oldParticipants
     * @param creator
     */
    public void updateParticipants(ModifyMeetingDto dto, Meeting oldMeeting, Meeting newMeeting,
                                   List<MeetParticipants> oldParticipants, Person creator) {
        // 排除删除的
        List<String> delOids = dto.getDelActors();
        // 不影响原集合
        List<MeetParticipants> oldUsers = new ArrayList<>(oldParticipants);

        if (CollectionUtils.isNotEmpty(delOids)) {
            Set<String> set = new HashSet<>(delOids);
            oldUsers.removeIf(p -> set.contains(p.getOid()));
        }

        logger.info("updateParticipants size: {}", oldUsers.size());

        String id = dto.getId();
        Map<String, Object> update = dto.toUpdate();
        // 更新会议与会者相关信息
        meetParticipantsDao.updateByMeetingChange(id, update);

        ThreadPoolUtils.execute(() -> {

            // 过滤出与会人
            List<MeetParticipants> ptUsers = filterPtList(oldUsers);

            // 新提醒策略
            NoticeTimeUtils.Strategy strategy = NoticeTimeUtils.compare(oldMeeting, newMeeting);
            // 会议信息发生变化
            boolean infoChanged = dto.needRepush(oldMeeting);

            // 过滤出隐藏的与会人
            List<MeetParticipants> showedList = ptUsers.stream().filter(p -> {
                p.setShow(userMustShow(newMeeting, p.getOid()));
                // 只对显示的做推送
                return p.isShow();
            }).collect(toList());

            // 提醒策略为更新提醒
            if (strategy.isOnlyUpdate()) {
                // 先删除原提醒
                meetingPushService.deleteScheduleNotice(newMeeting.getId());
            }
            for (List<MeetParticipants> pptUsers : PartitionUtils.partition(showedList)) {
                // 与会人oids
                Set<String> ptOids = extractOidSet(pptUsers);

                if (strategy.isChanged() || infoChanged) {

                    // 不能忽略才通知
                    if (!ignoreAllNotice(newMeeting)) {
                        // 任意发送了变化都推公众号
                        meetingPushService.pushPubaccFouUpdate(newMeeting, ptOids, creator);

                        // 提醒策略为新建提醒/更新提醒
                        if (strategy.isCreateOrJoin() || strategy.isOnlyUpdate()) {
                            // 原与会人创建新的定时提醒
                            meetingPushService.createOrJoinScheduleNotice(newMeeting, extractIdSet(pptUsers));

                            // 原与会人创建定时短信提醒
                            List<Person> persons = userCoreService.listPersonsByOidsAndEid(
                                    new ArrayList<>(ptOids), creator.getEid());
                            meetingPushService.createOrJoinScheduleSmsNotice(newMeeting, persons, creator, pptUsers);
                        }
                    }

                    // 提醒策略为取消提醒
                    if (strategy.isOnlyCancel()) {
                        // 原与会人取消推送
                        meetingPushService.cancelScheduleNotice(newMeeting.getId(), extractIdSet(pptUsers));
                    }
                }
                // 刷新卡片 带上创建人
                ptOids.add(creator.getoId());
                meetingPushService.refreshCard(ptOids);

                // 重建索引
                pptUsers.forEach(dto::modifyParticipant);
                meetingSearchService.refresh(pptUsers, newMeeting);
            }
        });
    }

    /**
     * 删除与会人
     *
     * @param delOids
     * @param oldMeeting
     * @param oldParticipants
     * @param creator
     */
    public void deleteParticipants(List<String> delOids, Meeting oldMeeting,
                                   List<MeetParticipants> oldParticipants, Person creator) {
        String meetingId = oldMeeting.getId();
        if (!delOids.isEmpty()) {
            ThreadPoolUtils.execute(() -> {
                // 删除对应与会人的信息
                meetParticipantsDao.deleteMeeting(meetingId, delOids);

                // 找出需要推送的
                Set<String> delOidSet = new HashSet<>(delOids);
                List<MeetParticipants> needPushUsers = oldParticipants.stream()
                        // 只对显示的做推送
                        .filter(p -> delOidSet.contains(p.getOid()) && p.isShow())
                        .collect(toList());
                List<String> ptIds = extractIdList(needPushUsers);
                List<String> oids = extractOidList(needPushUsers);

                // 删除定时提醒
                meetingPushService.cancelScheduleNotice(meetingId, ptIds);

                // 刷新卡片
                meetingPushService.refreshCard(oids);

                // 删除索引
                meetingSearchService.delete(meetingId, oids);

                // 不能忽略才通知
                if (!ignoreAllNotice(oldMeeting)) {
                    // 推送公众号消息
                    meetingPushService.pushPubaccForDelete(oldMeeting, creator, oids);
                }
            });
        }
        logger.info("deleteParticipants size: {}", delOids.size());
    }

    /**
     * 通过与会人和领导oid构建与会人
     *
     * @param actors
     * @param leaders
     * @param newMeeting
     * @param personMap
     * @return
     */
    private List<MeetParticipants> buildNewParticipantsOfActorsAndLeaders(List<String> actors, List<String> leaders,
                                                                          Meeting newMeeting, Map<String, Person> personMap) {
        List<MeetParticipants> result = new ArrayList<>();

        // 新增的与会人
        if (CollectionUtils.isNotEmpty(actors)) {
            List<MeetParticipants> savedList = buildNewParticipants(newMeeting, actors, personMap,
                    target -> {
                        target.setMeetingSource(WorkSource.PARTICIPANTS);
                        target.setShow(userMustShow(newMeeting, target.getOid()));

                        //  创建人在协作人里，那么这个协作人也要设置为参加状态
                        if (newMeeting.getOid().equals(target.getOid())) {
                            target.setMeetingNew(0);
                            target.setReadStatus(1);
                            target.setJoinStatus(2);
                        }
                    });
            result.addAll(savedList);
        }

        //新增领导
        if (CollectionUtils.isNotEmpty(leaders)) {
            List<MeetParticipants> savedList = buildNewParticipants(newMeeting, leaders, personMap,
                    target -> {
                        target.setMeetingSource(WorkSource.PARTICIPANTS);
                        target.setLeader(true);
                        target.setShow(userMustShow(newMeeting, target.getOid()));

                        //  创建人在协作人里，那么这个协作人也要设置为参加状态
                        if (newMeeting.getOid().equals(target.getOid())) {
                            target.setMeetingNew(0);
                            target.setReadStatus(1);
                            target.setJoinStatus(2);
                        }
                    });
            result.addAll(savedList);
        }
        return result;
    }

    /**
     * 构建创建人
     *
     * @param newMeeting
     * @param creator
     * @return
     */
    private MeetParticipants buildNewParticipantOfCreator(Meeting newMeeting, Person creator) {
        List<String> creatorOids = Collections.singletonList(creator.getoId());
        Map<String, Person> createPersonMap = ImmutableMap.of(creator.getoId(), creator);
        List<MeetParticipants> singletonList = buildNewParticipants(newMeeting, creatorOids, createPersonMap,
                target -> {
                    target.setMeetingSource(WorkSource.CREATOR);
                    target.setMeetingNew(0);
                    target.setReadStatus(1);
                    target.setJoinStatus(2);
                    // 开始时间同会议创建时间按时间排序时发起人会在其他与会人前面
                    target.setCreateTime(newMeeting.getCreateTime());
                    target.setTopDate(target.getTopState() == 1 ? newMeeting.getCreateTime() : null);
                });
        return singletonList.get(0);
    }

    /**
     * 处理新与会人(新增会议)
     *
     * @param sources
     * @param newMeeting
     * @param creator
     * @param personMap
     */
    public void processNewParticipants(List<MeetParticipants> sources, Meeting newMeeting,
                                       Person creator, Map<String, Person> personMap) {
        for (List<MeetParticipants> list : PartitionUtils.partition(sources)) {
            // 入库，增加日程创建成功后的与会人可见性，不使用异步
            meetParticipantsDao.insert(list);
        }

        processParticipants(sources, newMeeting, creator, personMap);
    }

    public void processParticipants(List<MeetParticipants> sources, Meeting newMeeting,
                                    Person creator, Map<String, Person> personMap) {
        ThreadPoolUtils.execute(() -> {
            List<Person> persons = new ArrayList<>(personMap.values());

            // 只对显示的做推送
            List<MeetParticipants> showedList = filterShowedList(sources);

            logger.info("process oidList: {}", extractOidList(showedList));

            for (List<MeetParticipants> list : PartitionUtils.partition(showedList)) {
                // 刷新卡片
                meetingPushService.refreshCard(extractOidList(list));

                // 重建索引
                meetingSearchService.refresh(list, newMeeting);

                // 忽略所有通知
                if (ignoreAllNotice(newMeeting)) {
                    continue;
                }

                // New头像
                cooperationNewWorkDao.batchSave(buildNewAvatars(newMeeting, creator, personMap, list));

                // 与会人oid，发起人不发通知
                List<String> pushOids = extractPtOidList(list);
                pushOids.remove(creator.getoId());

                // 与会人显示app弹窗
                String creatorName = StringUtils.defaultString(creator.getName());
                meetingPushService.showAppDialog(newMeeting, pushOids, creatorName);

                // 与会人显示app待办
                meetingPushService.showTodo(newMeeting, pushOids, creatorName);

                // 过滤出与会人
                List<MeetParticipants> ptUsers = filterPtList(list);
                // 保存与会人定时提醒
                meetingPushService.createOrJoinScheduleNotice(newMeeting, extractIdSet(ptUsers));
                // 保存与会人定时短信提醒
                meetingPushService.createOrJoinScheduleSmsNotice(newMeeting, persons, creator, ptUsers);
            }
        });
    }

    /**
     * 生成新与会人
     *
     * @param meeting
     * @param oids
     * @param personMap
     * @param consumer
     * @return
     */
    private List<MeetParticipants> buildNewParticipants(Meeting meeting, List<String> oids,
                                                        Map<String, Person> personMap,
                                                        Consumer<MeetParticipants> consumer) {
        return oids.stream()
                .filter(personMap::containsKey)
                .map(oid -> {
                    Person person = personMap.get(oid);
                    MeetParticipants item = MapperHelper.map(meeting, MeetParticipants.class);
                    // 先填充
                    item.setId(ObjectId.get().toString());
                    item.setMeetingId(meeting.getId());
                    item.setTopState(0);
                    item.setTopDate(null);
                    item.setJoinDate(new Date());
                    item.setMeetingStatus(0);
                    item.setOid(person.getoId());
                    item.setPersonnelSource(1);
                    item.setPersonName(person.getName());
                    item.setPhotoUrl(person.getPhotoUrl());
                    item.setEid(person.getEid());
                    item.setUserId(person.getWbUserId());
                    item.setRemindStatus(0);// 未提醒
                    item.setMeetingNew(-1);
                    item.setCreateTime(new Date());

                    // 外部填充
                    consumer.accept(item);
                    return item;
                })
                .collect(toList());
    }

    private List<CooperationNewWork> buildNewAvatars(Meeting newMeeting, Person creator,
                                                     Map<String, Person> personMap,
                                                     List<MeetParticipants> list) {
        List<CooperationNewWork> newAvatars = new ArrayList<>();
        for (MeetParticipants item : list) {
            Person person = personMap.get(item.getOid());
            if (person == null) {
                continue;
            }
            // 创建人自己不需要New头像
            if (!StringUtils.equals(person.getWbUserId(), creator.getWbUserId())) {
                newAvatars.add(BeanHelper.buildCooperationNewWork(newMeeting, person, creator));
            }
        }
        return newAvatars;
    }

    private Map<String, Person> preparePersonMap(List<String> actors, List<String> leaders, Person creator) {
        List<String> oids = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(actors)) {
            oids.addAll(actors);
        }
        if (CollectionUtils.isNotEmpty(leaders)) {
            oids.addAll(leaders);
        }

        // 准备person信息
        return preparePersons(creator.getEid(), oids);
    }

    private Map<String, Person> preparePersons(String eid, List<String> oids) {
        List<Person> persons = new ArrayList<>(oids.size());
        for (List<String> partitionIds : PartitionUtils.partition(oids)) {
            persons.addAll(userCoreService.listPersonsByOidsAndEid(partitionIds, eid));
        }
        return PersonUtils.mapPersonByOid(persons);
    }


    public MeetParticipants findOne(String meetingId, String oid) {
        return meetParticipantsDao.findByMeetingIdAndOid(meetingId, oid);
    }

    public void updateApprove(String meetingId) {
        logger.info("updateApprove, meetingId:{}", meetingId);

        Meeting meeting = meetingDao.findByIdFromPrimary(meetingId);
        Person creator = userCoreService.requirePersonByOid(meeting.getOid());

        // 更新会议审批状态
        Map<String, Object> param = new HashMap<>();
        param.put("approve", 2);
        param.put("updateTime", new Date());  // dao update 方法会默认传string
        meetingDao.update(param, meetingId);
        meetParticipantsDao.modifyIsShow(meetingId, true);

        // 组装数据
        List<MeetParticipants> partitions = meetParticipantsDao.findByMeetingIdForUpdate(meetingId);
        partitions.forEach(p -> p.setShow(true));
        List<String> oids = partitions.stream().map(MeetParticipants::getOid).collect(toList());
        Map<String, Person> personMap = preparePersons(meeting.getEid(), oids);

        // 先删除提醒
        meetingPushService.deleteScheduleNotice(meetingId);

        // 推送通知
        processParticipants(partitions, meeting, creator, personMap);
    }

}
