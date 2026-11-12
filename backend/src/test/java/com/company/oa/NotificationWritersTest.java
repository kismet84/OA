package com.company.oa;

import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Guard the existing domain wording, recipients and links while consolidating writes. */
class NotificationWritersTest {
  final JdbcTemplate db=mock(JdbcTemplate.class);
  final Auth auth=mock(Auth.class);
  final NotificationService notices=mock(NotificationService.class);
  @Test void approvalAssetContractVisitorKeepOriginalWordingAndLinks() {
    var workflow=new WorkflowService(db,auth,Clock.systemUTC()); workflow.notifications=notices;
    workflow.notify(7,"审批单 #9 已批准");
    verify(notices).write(7L,"审批单 #9 已批准");
    var assets=new AssetController(db,auth); assets.notifications=notices;
    assets.notifyApplicant(Map.of("user_id",7L,"id",9L),"已发放");
    verify(notices).write(7L,"资产领用：已发放","asset_request_id",9L);
    var contracts=new ContractController(db,auth,Clock.systemUTC(),mock(AttachmentController.class)); contracts.notifications=notices;
    contracts.notice(7,9,"已批准");
    verify(notices).write(7L,"合同 #9：已批准","contract_id",9L);
    var visitors=new VisitorController(db,auth,Clock.systemUTC()); visitors.notifications=notices;
    visitors.notice(9,7,"张访客");
    verify(notices).write(7L,"访客「张访客」已到访，请接待（来访 #9）","visitor_id",9L);
  }
  @Test void taskMeetingDocumentKeepOriginalLinksAndRecipientExclusions() {
    var tasks=new TaskController(db,auth); tasks.notifications=notices;
    tasks.notify(7,8,9,"任务已修改：验收"); tasks.notify(8,8,9,"本人操作");
    verify(notices).write(7L,"任务已修改：验收","task_id",9L);
    verify(notices,never()).write(eq(8L),anyString(),anyString(),any());
    var calendar=new CalendarController(db,auth); calendar.notifications=notices;
    calendar.notify(7,9,"会议已取消：验收");
    verify(notices).write(7L,"会议已取消：验收","meeting_id",9L);
    var documents=new DocumentController(db,auth,mock(AttachmentController.class),mock(DocumentMedia.class));
    documents.notifications=notices;
    when(db.queryForObject("SELECT title FROM documents WHERE id=?",String.class,9L)).thenReturn("制度");
    documents.notify(9,Set.of(7L,8L),new Actor(8,"操作者","EMPLOYEE",false,0));
    verify(notices).write(7L,"文档资料已发布：制度","document_id",9L);
    verify(notices,never()).write(eq(8L),anyString(),anyString(),any());
  }
}
