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
     * ???????????????
     *
     * @param dto
     * @param newMeeting
     * @param creator
     * @return
     */
    public List<MeetParticipants> createParticipants(CreateMeetingDto dto, Meeting newMeeting, Person creator) {
        // ???????????????????????????
        Map<String, Person> personMap = preparePersonMap(dto.getActors(), dto.getLeaders(), creator);

        // ??????????????????
        List<MeetParticipants> participants = buildNewParticipantsOfActorsAndLeaders(dto.getActors(),
                dto.getLeaders(), newMeeting, personMap);

        // ?????????????????????????????????????????????????????????
        MeetParticipants participantOfCreator = buildNewParticipantOfCreator(newMeeting, creator);
        // ??????
        participants.add(0, participantOfCreator);

        // ???????????? ??????New?????????app???????????????????????????????????????es????????????????????????
        processNewParticipants(participants, newMeeting, creator, personMap);

        logger.info("createParticipants size: {}", participants.size());
        return participants;
    }

    /**
     * ?????????????????????????????????
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
        // ???????????????
        if (CollectionUtils.isNotEmpty(addActors)) {
            oldParticipants.forEach(old -> {
                addActors.removeIf(oid -> Objects.equals(old.getOid(), oid));
            });
        }

        // ???????????????????????????
        Map<String, Person> personMap = preparePersonMap(dto.getAddActors(), dto.getAddLeaders(), creator);
        if (personMap.isEmpty()) {
            return Collections.emptyList();
        }

        // ??????????????????
        List<MeetParticipants> participants = buildNewParticipantsOfActorsAndLeaders(dto.getAddActors(),
                dto.getAddLeaders(), newMeeting, personMap);

        // ???????????? ??????New?????????app???????????????????????????????????????es????????????????????????
        processNewParticipants(participants, newMeeting, creator, personMap);

        logger.info("createParticipantsForUpdate size: {}", participants.size());
        return participants;
    }

    /**
     * ???????????????
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
        // ???????????????
        List<String> delOids = dto.getDelActors();
        // ??????????????????
        List<MeetParticipants> oldUsers = new ArrayList<>(oldParticipants);

        if (CollectionUtils.isNotEmpty(delOids)) {
            Set<String> set = new HashSet<>(delOids);
            oldUsers.removeIf(p -> set.contains(p.getOid()));
        }

        logger.info("updateParticipants size: {}", oldUsers.size());

        String id = dto.getId();
        Map<String, Object> update = dto.toUpdate();
        // ?????????????????????????????????
        meetParticipantsDao.updateByMeetingChange(id, update);

        ThreadPoolUtils.execute(() -> {

            // ??????????????????
            List<MeetParticipants> ptUsers = filterPtList(oldUsers);

            // ???????????????
            NoticeTimeUtils.Strategy strategy = NoticeTimeUtils.compare(oldMeeting, newMeeting);
            // ????????????????????????
            boolean infoChanged = dto.needRepush(oldMeeting);

            // ???????????????????????????
            List<MeetParticipants> showedList = ptUsers.stream().filter(p -> {
                p.setShow(userMustShow(newMeeting, p.getOid()));
                // ????????????????????????
                return p.isShow();
            }).collect(toList());

            // ???????????????????????????
            if (strategy.isOnlyUpdate()) {
                // ??????????????????
                meetingPushService.deleteScheduleNotice(newMeeting.getId());
            }
            for (List<MeetParticipants> pptUsers : PartitionUtils.partition(showedList)) {
                // ?????????oids
                Set<String> ptOids = extractOidSet(pptUsers);

                if (strategy.isChanged() || infoChanged) {

                    // ?????????????????????
                    if (!ignoreAllNotice(newMeeting)) {
                        // ????????????????????????????????????
                        meetingPushService.pushPubaccFouUpdate(newMeeting, ptOids, creator);

                        // ???????????????????????????/????????????
                        if (strategy.isCreateOrJoin() || strategy.isOnlyUpdate()) {
                            // ????????????????????????????????????
                            meetingPushService.createOrJoinScheduleNotice(newMeeting, extractIdSet(pptUsers));

                            // ????????????????????????????????????
                            List<Person> persons = userCoreService.listPersonsByOidsAndEid(
                                    new ArrayList<>(ptOids), creator.getEid());
                            meetingPushService.createOrJoinScheduleSmsNotice(newMeeting, persons, creator, pptUsers);
                        }
                    }

                    // ???????????????????????????
                    if (strategy.isOnlyCancel()) {
                        // ????????????????????????
                        meetingPushService.cancelScheduleNotice(newMeeting.getId(), extractIdSet(pptUsers));
                    }
                }
                // ???????????? ???????????????
                ptOids.add(creator.getoId());
                meetingPushService.refreshCard(ptOids);

                // ????????????
                pptUsers.forEach(dto::modifyParticipant);
                meetingSearchService.refresh(pptUsers, newMeeting);
            }
        });
    }

    /**
     * ???????????????
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
                // ??????????????????????????????
                meetParticipantsDao.deleteMeeting(meetingId, delOids);

                // ?????????????????????
                Set<String> delOidSet = new HashSet<>(delOids);
                List<MeetParticipants> needPushUsers = oldParticipants.stream()
                        // ????????????????????????
                        .filter(p -> delOidSet.contains(p.getOid()) && p.isShow())
                        .collect(toList());
                List<String> ptIds = extractIdList(needPushUsers);
                List<String> oids = extractOidList(needPushUsers);

                // ??????????????????
                meetingPushService.cancelScheduleNotice(meetingId, ptIds);

                // ????????????
                meetingPushService.refreshCard(oids);

                // ????????????
                meetingSearchService.delete(meetingId, oids);

                // ?????????????????????
                if (!ignoreAllNotice(oldMeeting)) {
                    // ?????????????????????
                    meetingPushService.pushPubaccForDelete(oldMeeting, creator, oids);
                }
            });
        }
        logger.info("deleteParticipants size: {}", delOids.size());
    }

    /**
     * ????????????????????????oid???????????????
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

        // ??????????????????
        if (CollectionUtils.isNotEmpty(actors)) {
            List<MeetParticipants> savedList = buildNewParticipants(newMeeting, actors, personMap,
                    target -> {
                        target.setMeetingSource(WorkSource.PARTICIPANTS);
                        target.setShow(userMustShow(newMeeting, target.getOid()));

                        //  ???????????????????????????????????????????????????????????????????????????
                        if (newMeeting.getOid().equals(target.getOid())) {
                            target.setMeetingNew(0);
                            target.setReadStatus(1);
                            target.setJoinStatus(2);
                        }
                    });
            result.addAll(savedList);
        }

        //????????????
        if (CollectionUtils.isNotEmpty(leaders)) {
            List<MeetParticipants> savedList = buildNewParticipants(newMeeting, leaders, personMap,
                    target -> {
                        target.setMeetingSource(WorkSource.PARTICIPANTS);
                        target.setLeader(true);
                        target.setShow(userMustShow(newMeeting, target.getOid()));

                        //  ???????????????????????????????????????????????????????????????????????????
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
     * ???????????????
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
                    // ???????????????????????????????????????????????????????????????????????????????????????
                    target.setCreateTime(newMeeting.getCreateTime());
                    target.setTopDate(target.getTopState() == 1 ? newMeeting.getCreateTime() : null);
                });
        return singletonList.get(0);
    }

    /**
     * ??????????????????(????????????)
     *
     * @param sources
     * @param newMeeting
     * @param creator
     * @param personMap
     */
    public void processNewParticipants(List<MeetParticipants> sources, Meeting newMeeting,
                                       Person creator, Map<String, Person> personMap) {
        for (List<MeetParticipants> list : PartitionUtils.partition(sources)) {
            // ???????????????????????????????????????????????????????????????????????????
            meetParticipantsDao.insert(list);
        }

        processParticipants(sources, newMeeting, creator, personMap);
    }

    public void processParticipants(List<MeetParticipants> sources, Meeting newMeeting,
                                    Person creator, Map<String, Person> personMap) {
        ThreadPoolUtils.execute(() -> {
            List<Person> persons = new ArrayList<>(personMap.values());

            // ????????????????????????
            List<MeetParticipants> showedList = filterShowedList(sources);

            logger.info("process oidList: {}", extractOidList(showedList));

            for (List<MeetParticipants> list : PartitionUtils.partition(showedList)) {
                // ????????????
                meetingPushService.refreshCard(extractOidList(list));

                // ????????????
                meetingSearchService.refresh(list, newMeeting);

                // ??????????????????
                if (ignoreAllNotice(newMeeting)) {
                    continue;
                }

                // New??????
                cooperationNewWorkDao.batchSave(buildNewAvatars(newMeeting, creator, personMap, list));

                // ?????????oid????????????????????????
                List<String> pushOids = extractPtOidList(list);
                pushOids.remove(creator.getoId());

                // ???????????????app??????
                String creatorName = StringUtils.defaultString(creator.getName());
                meetingPushService.showAppDialog(newMeeting, pushOids, creatorName);

                // ???????????????app??????
                meetingPushService.showTodo(newMeeting, pushOids, creatorName);

                // ??????????????????
                List<MeetParticipants> ptUsers = filterPtList(list);
                // ???????????????????????????
                meetingPushService.createOrJoinScheduleNotice(newMeeting, extractIdSet(ptUsers));
                // ?????????????????????????????????
                meetingPushService.createOrJoinScheduleSmsNotice(newMeeting, persons, creator, ptUsers);
            }
        });
    }

    /**
     * ??????????????????
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
                    // ?????????
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
                    item.setRemindStatus(0);// ?????????
                    item.setMeetingNew(-1);
                    item.setCreateTime(new Date());

                    // ????????????
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
            // ????????????????????????New??????
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

        // ??????person??????
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

        // ????????????????????????
        Map<String, Object> param = new HashMap<>();
        param.put("approve", 2);
        param.put("updateTime", new Date());  // dao update ??????????????????string
        meetingDao.update(param, meetingId);
        meetParticipantsDao.modifyIsShow(meetingId, true);

        // ????????????
        List<MeetParticipants> partitions = meetParticipantsDao.findByMeetingIdForUpdate(meetingId);
        partitions.forEach(p -> p.setShow(true));
        List<String> oids = partitions.stream().map(MeetParticipants::getOid).collect(toList());
        Map<String, Person> personMap = preparePersons(meeting.getEid(), oids);

        // ???????????????
        meetingPushService.deleteScheduleNotice(meetingId);

        // ????????????
        processParticipants(partitions, meeting, creator, personMap);
    }

}
