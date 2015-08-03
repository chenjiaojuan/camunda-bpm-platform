/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.camunda.bpm.engine.test.bpmn.event.compensate;

import static org.camunda.bpm.engine.test.util.ActivityInstanceAssert.assertThat;
import static org.camunda.bpm.engine.test.util.ActivityInstanceAssert.describeActivityInstanceTree;
import static org.camunda.bpm.engine.test.util.ExecutionAssert.assertThat;
import static org.camunda.bpm.engine.test.util.ExecutionAssert.describeExecutionTree;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.history.HistoricVariableInstanceQuery;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.test.PluggableProcessEngineTestCase;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.bpmn.event.compensate.helper.BookFlightService;
import org.camunda.bpm.engine.test.bpmn.event.compensate.helper.CancelFlightService;
import org.camunda.bpm.engine.test.bpmn.event.compensate.helper.GetVariablesDelegate;
import org.camunda.bpm.engine.test.bpmn.event.compensate.helper.SetVariablesDelegate;
import org.camunda.bpm.engine.test.util.ExecutionTree;

/**
 * @author Daniel Meyer
 */
public class CompensateEventTest extends PluggableProcessEngineTestCase {

  @Deployment
  public void testCompensateSubprocess() {

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    assertEquals(5, runtimeService.getVariable(processInstance.getId(), "undoBookHotel"));

    runtimeService.signal(processInstance.getId());
    assertProcessEnded(processInstance.getId());

  }

  @Deployment
  public void testCompensateSubprocessInsideSubprocess() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    completeTask("Book Hotel");
    completeTask("Book Flight");

    // throw compensation event
    completeTask("throw compensation");

    // execute compensation handlers
    completeTask("Cancel Hotel");
    completeTask("Cancel Flight");

    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensateParallelSubprocess() {

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    assertEquals(5, runtimeService.getVariable(processInstance.getId(), "undoBookHotel"));

    Task singleResult = taskService.createTaskQuery().singleResult();
    taskService.complete(singleResult.getId());

    runtimeService.signal(processInstance.getId());
    assertProcessEnded(processInstance.getId());

  }

  @Deployment
  public void testCompensateParallelSubprocessCompHandlerWaitstate() {

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    List<Task> compensationHandlerTasks = taskService.createTaskQuery().taskDefinitionKey("undoBookHotel").list();
    assertEquals(5, compensationHandlerTasks.size());

    ActivityInstance rootActivityInstance = runtimeService.getActivityInstance(processInstance.getId());
    List<ActivityInstance> compensationHandlerInstances = getInstancesForActivityId(rootActivityInstance, "undoBookHotel");
    assertEquals(5, compensationHandlerInstances.size());

    for (Task task : compensationHandlerTasks) {
      taskService.complete(task.getId());
    }

    Task singleResult = taskService.createTaskQuery().singleResult();
    taskService.complete(singleResult.getId());

    runtimeService.signal(processInstance.getId());
    assertProcessEnded(processInstance.getId());
  }

  @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensateParallelSubprocessCompHandlerWaitstate.bpmn20.xml")
  public void testDeleteParallelSubprocessCompHandlerWaitstate() {
    // given
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    // five inner tasks
    List<Task> compensationHandlerTasks = taskService.createTaskQuery().taskDefinitionKey("undoBookHotel").list();
    assertEquals(5, compensationHandlerTasks.size());

    // when
    runtimeService.deleteProcessInstance(processInstance.getId(), "");

    // then the process has been removed
    assertProcessEnded(processInstance.getId());
  }

  @Deployment
  public void testCompensateMiSubprocess() {

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    assertEquals(5, runtimeService.getVariable(processInstance.getId(), "undoBookHotel"));

    runtimeService.signal(processInstance.getId());
    assertProcessEnded(processInstance.getId());

  }

  @Deployment
  public void testCompensateScope() {

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    assertEquals(5, runtimeService.getVariable(processInstance.getId(), "undoBookHotel"));
    assertEquals(5, runtimeService.getVariable(processInstance.getId(), "undoBookFlight"));

    runtimeService.signal(processInstance.getId());
    assertProcessEnded(processInstance.getId());

  }

  // See: https://app.camunda.com/jira/browse/CAM-1410
  @Deployment
  public void testCompensateActivityRef() {

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    assertEquals(5, runtimeService.getVariable(processInstance.getId(), "undoBookHotel"));
    assertNull(runtimeService.getVariable(processInstance.getId(), "undoBookFlight"));

    runtimeService.signal(processInstance.getId());
    assertProcessEnded(processInstance.getId());

  }

  /**
   * CAM-3628
   */
  @Deployment
  public void testCompensateSubprocessWithBoundaryEvent() {
    ProcessInstance instance = runtimeService.startProcessInstanceByKey("compensateProcess");

    Task compensationTask = taskService.createTaskQuery().singleResult();
    assertNotNull(compensationTask);
    assertEquals("undoSubprocess", compensationTask.getTaskDefinitionKey());

    taskService.complete(compensationTask.getId());
    runtimeService.signal(instance.getId());
    assertProcessEnded(instance.getId());
  }

  @Deployment
  public void testCompensateActivityInSubprocess() {
    // given
    ProcessInstance instance = runtimeService.startProcessInstanceByKey("compensateProcess");

    Task scopeTask = taskService.createTaskQuery().singleResult();
    taskService.complete(scopeTask.getId());

    // process has not yet thrown compensation
    // when throw compensation
    runtimeService.signal(instance.getId());
    // then
    Task compensationTask = taskService.createTaskQuery().singleResult();
    assertNotNull(compensationTask);
    assertEquals("undoScopeTask", compensationTask.getTaskDefinitionKey());

    taskService.complete(compensationTask.getId());
    runtimeService.signal(instance.getId());
    assertProcessEnded(instance.getId());
  }

  @Deployment
  public void testCompensateActivityInConcurrentSubprocess() {
    // given
    ProcessInstance instance = runtimeService.startProcessInstanceByKey("compensateProcess");

    Task scopeTask = taskService.createTaskQuery().taskDefinitionKey("scopeTask").singleResult();
    taskService.complete(scopeTask.getId());

    Task outerTask = taskService.createTaskQuery().taskDefinitionKey("outerTask").singleResult();
    taskService.complete(outerTask.getId());

    // process has not yet thrown compensation
    // when throw compensation
    runtimeService.signal(instance.getId());

    // then
    Task compensationTask = taskService.createTaskQuery().singleResult();
    assertNotNull(compensationTask);
    assertEquals("undoScopeTask", compensationTask.getTaskDefinitionKey());

    taskService.complete(compensationTask.getId());
    runtimeService.signal(instance.getId());
    assertProcessEnded(instance.getId());
  }

  @Deployment
  public void testCompensateConcurrentMiActivity() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    // complete 4 of 5 user tasks
    completeTasks("Book Hotel", 4);

    // throw compensation event
    completeTaskWithVariable("Request Vacation", "accept", false);

    // should not compensate activity before multi instance activity is completed
    assertEquals(0, taskService.createTaskQuery().taskName("Cancel Hotel").count());

    // complete last open task and end process instance
    completeTask("Book Hotel");
    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensateConcurrentMiSubprocess() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    // complete 4 of 5 user tasks
    completeTasks("Book Hotel", 4);

    // throw compensation event
    completeTaskWithVariable("Request Vacation", "accept", false);

    // should not compensate activity before multi instance activity is completed
    assertEquals(0, taskService.createTaskQuery().taskName("Cancel Hotel").count());

    // complete last open task and end process instance
    completeTask("Book Hotel");

    runtimeService.signal(processInstanceId);
    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensateActivityRefMiActivity() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    completeTasks("Book Hotel", 5);

    // throw compensation event for activity
    completeTaskWithVariable("Request Vacation", "accept", false);

    // execute compensation handlers for each execution of the subprocess
    assertEquals(5, taskService.createTaskQuery().count());
    completeTasks("Cancel Hotel", 5);

    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensateActivityRefMiSubprocess() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    completeTasks("Book Hotel", 5);

    // throw compensation event for activity
    completeTaskWithVariable("Request Vacation", "accept", false);

    // execute compensation handlers for each execution of the subprocess
    assertEquals(5, taskService.createTaskQuery().count());
    completeTasks("Cancel Hotel", 5);

    assertProcessEnded(processInstanceId);
  }

  @Deployment(resources = { "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCallActivityCompensationHandler.bpmn20.xml",
      "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationHandler.bpmn20.xml" })
  public void testCallActivityCompensationHandler() {

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    if (!processEngineConfiguration.getHistory().equals(ProcessEngineConfiguration.HISTORY_NONE)) {
      assertEquals(5, historyService.createHistoricActivityInstanceQuery().activityId("undoBookHotel").count());
    }

    runtimeService.signal(processInstance.getId());
    assertProcessEnded(processInstance.getId());

    assertEquals(0, runtimeService.createProcessInstanceQuery().count());

    if (!processEngineConfiguration.getHistory().equals(ProcessEngineConfiguration.HISTORY_NONE)) {
      assertEquals(6, historyService.createHistoricProcessInstanceQuery().count());
    }

  }

  @Deployment
  public void testCompensateMiSubprocessVariableSnapshots() {
    // see referenced java delegates in the process definition.

    List<String> hotels = Arrays.asList("Rupert", "Vogsphere", "Milliways", "Taunton", "Ysolldins");

    SetVariablesDelegate.setValues(hotels);

    // SetVariablesDelegate take the first element of static list and set the value as local variable
    // GetVariablesDelegate read the variable and add the value to static list

    runtimeService.startProcessInstanceByKey("compensateProcess");

    if (!processEngineConfiguration.getHistory().equals(ProcessEngineConfiguration.HISTORY_NONE)) {
      assertEquals(5, historyService.createHistoricActivityInstanceQuery().activityId("undoBookHotel").count());
    }

    assertTrue(GetVariablesDelegate.values.containsAll(hotels));
  }

  @Deployment
  public void testCompensateMiSubprocessWithCompensationEventSubprocessVariableSnapshots() {
    // see referenced java delegates in the process definition.

    List<String> hotels = Arrays.asList("Rupert", "Vogsphere", "Milliways", "Taunton", "Ysolldins");

    SetVariablesDelegate.setValues(hotels);

    // SetVariablesDelegate take the first element of static list and set the value as local variable
    // GetVariablesDelegate read the variable and add the value to static list

    runtimeService.startProcessInstanceByKey("compensateProcess");

    if (!processEngineConfiguration.getHistory().equals(ProcessEngineConfiguration.HISTORY_NONE)) {
      assertEquals(5, historyService.createHistoricActivityInstanceQuery().activityId("undoBookHotel").count());
    }

    assertTrue(GetVariablesDelegate.values.containsAll(hotels));
  }

  /**
   * enable test case when bug is fixed
   *
   * @see https://app.camunda.com/jira/browse/CAM-4268
   */
  @Deployment
  public void FAILING_testCompensateMiSubprocessVariableSnapshotOfElementVariable() {
    Map<String, Object> variables = new HashMap<String, Object>();
    // multi instance collection
    List<String> flights = Arrays.asList("STS-14", "STS-28");
    variables.put("flights", flights);

    // see referenced java delegates in the process definition
    // java delegates read element variable (flight) and add the variable value
    // to a static list
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess", variables);

    if (!processEngineConfiguration.getHistory().equals(ProcessEngineConfiguration.HISTORY_NONE)) {
      assertEquals(flights.size(), historyService.createHistoricActivityInstanceQuery().activityId("undoBookFlight").count());
    }

    // java delegates should be invoked for each element in collection
    assertEquals(flights, BookFlightService.bookedFlights);
    assertEquals(flights, CancelFlightService.canceledFlights);

    assertProcessEnded(processInstance.getId());
  }

  @Deployment(resources = {
      "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensationTriggeredByEventSubProcessActivityRef.bpmn20.xml" })
  public void testCompensateActivityRefTriggeredByEventSubprocess() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");
    assertProcessEnded(processInstance.getId());

    HistoricVariableInstanceQuery historicVariableInstanceQuery = historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(processInstance.getId()).variableName("undoBookHotel");

    if (processEngineConfiguration.getHistoryLevel().getId() >= ProcessEngineConfigurationImpl.HISTORYLEVEL_AUDIT) {
      assertEquals(1, historicVariableInstanceQuery.count());
      assertEquals("undoBookHotel", historicVariableInstanceQuery.list().get(0).getVariableName());
      assertEquals(5, historicVariableInstanceQuery.list().get(0).getValue());

      assertEquals(0, historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstance.getId()).variableName("undoBookFlight").count());
    }
  }

  @Deployment(resources = {
      "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensationTriggeredByEventSubProcessInSubProcessActivityRef.bpmn20.xml" })
  public void testCompensateActivityRefTriggeredByEventSubprocessInSubProcess() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");
    assertProcessEnded(processInstance.getId());

    HistoricVariableInstanceQuery historicVariableInstanceQuery = historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(processInstance.getId()).variableName("undoBookHotel");

    if (processEngineConfiguration.getHistoryLevel().getId() >= ProcessEngineConfigurationImpl.HISTORYLEVEL_AUDIT) {
      assertEquals(1, historicVariableInstanceQuery.count());
      assertEquals("undoBookHotel", historicVariableInstanceQuery.list().get(0).getVariableName());
      assertEquals(5, historicVariableInstanceQuery.list().get(0).getValue());

      assertEquals(0, historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstance.getId()).variableName("undoBookFlight").count());
    }
  }

  @Deployment(resources = { "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensationInEventSubProcessActivityRef.bpmn20.xml" })
  public void testCompensateActivityRefInEventSubprocess() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");
    assertProcessEnded(processInstance.getId());

    HistoricVariableInstanceQuery historicVariableInstanceQuery = historyService.createHistoricVariableInstanceQuery().variableName("undoBookSecondHotel");

    if (processEngineConfiguration.getHistoryLevel().getId() >= ProcessEngineConfigurationImpl.HISTORYLEVEL_AUDIT) {
      assertEquals(1, historicVariableInstanceQuery.count());
      assertEquals("undoBookSecondHotel", historicVariableInstanceQuery.list().get(0).getVariableName());
      assertEquals(5, historicVariableInstanceQuery.list().get(0).getValue());

      assertEquals(0, historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstance.getId()).variableName("undoBookFlight").count());

      assertEquals(0, historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstance.getId()).variableName("undoBookHotel").count());
    }
  }

  /**
   * enable test case when bug is fixed
   *
   * @see https://app.camunda.com/jira/browse/CAM-4304
   */
  @Deployment(resources = { "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensationInEventSubProcess.bpmn20.xml" })
  public void FAILING_testCompensateInEventSubprocess() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");
    assertProcessEnded(processInstance.getId());

    HistoricVariableInstanceQuery historicVariableInstanceQuery = historyService.createHistoricVariableInstanceQuery().variableName("undoBookSecondHotel");

    if (processEngineConfiguration.getHistoryLevel().getId() >= ProcessEngineConfigurationImpl.HISTORYLEVEL_AUDIT) {
      assertEquals(1, historicVariableInstanceQuery.count());
      assertEquals("undoBookSecondHotel", historicVariableInstanceQuery.list().get(0).getVariableName());
      assertEquals(5, historicVariableInstanceQuery.list().get(0).getValue());

      historicVariableInstanceQuery = historyService.createHistoricVariableInstanceQuery().variableName("undoBookFlight");

      assertEquals(1, historicVariableInstanceQuery.count());
      assertEquals(5, historicVariableInstanceQuery.list().get(0).getValue());

      historicVariableInstanceQuery = historyService.createHistoricVariableInstanceQuery().variableName("undoBookHotel");

      assertEquals(1, historicVariableInstanceQuery.count());
      assertEquals(5, historicVariableInstanceQuery.list().get(0).getValue());
    }
  }

  @Deployment
  public void testExecutionListeners() {
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put("start", 0);
    variables.put("end", 0);

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("testProcess", variables);

    int started = (Integer) runtimeService.getVariable(processInstance.getId(), "start");
    assertEquals(5, started);

    int ended = (Integer) runtimeService.getVariable(processInstance.getId(), "end");
    assertEquals(5, ended);

    int historyLevel = processEngineConfiguration.getHistoryLevel().getId();
    if (historyLevel > ProcessEngineConfigurationImpl.HISTORYLEVEL_NONE) {
      long finishedCount = historyService.createHistoricActivityInstanceQuery().activityId("undoBookHotel").finished().count();
      assertEquals(5, finishedCount);
    }
  }

  @Deployment
  public void testActivityInstanceTreeWithoutEventScope() {
    // given
    ProcessInstance instance = runtimeService.startProcessInstanceByKey("process");
    String processInstanceId = instance.getId();

    // when
    String taskId = taskService.createTaskQuery().singleResult().getId();
    taskService.complete(taskId);

    // then
    ActivityInstance tree = runtimeService.getActivityInstance(processInstanceId);
    assertThat(tree).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("task")
        .done());
  }

  @Deployment
  public void testConcurrentExecutionsAndPendingCompensation() {
    // given
    ProcessInstance instance = runtimeService.startProcessInstanceByKey("process");
    String processInstanceId = instance.getId();
    String taskId = taskService.createTaskQuery().taskDefinitionKey("innerTask").singleResult().getId();

    // when (1)
    taskService.complete(taskId);

    // then (1)
    ExecutionTree executionTree = ExecutionTree.forExecution(processInstanceId, processEngine);
    assertThat(executionTree).matches(
        describeExecutionTree(null)
        .scope()
          .child("task1").concurrent().noScope().up()
          .child("task2").concurrent().noScope().up()
          .child("subProcess").eventScope().scope().up()
        .done());

    ActivityInstance tree = runtimeService.getActivityInstance(processInstanceId);
    assertThat(tree).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("task1")
          .activity("task2")
        .done());

    // when (2)
    taskId = taskService.createTaskQuery().taskDefinitionKey("task1").singleResult().getId();
    taskService.complete(taskId);

    // then (2)
    executionTree = ExecutionTree.forExecution(processInstanceId, processEngine);
    assertThat(executionTree).matches(
        describeExecutionTree("task2")
        .scope()
          .child("subProcess").eventScope().scope().up()
        .done());

    tree = runtimeService.getActivityInstance(processInstanceId);
    assertThat(tree).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("task2")
        .done());

    // when (3)
    taskId = taskService.createTaskQuery().taskDefinitionKey("task2").singleResult().getId();
    taskService.complete(taskId);

    // then (3)
    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensationEndEventWithScope() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    if (!processEngineConfiguration.getHistory().equals(ProcessEngineConfiguration.HISTORY_NONE)) {
      assertEquals(5, historyService.createHistoricActivityInstanceQuery().activityId("undoBookHotel").count());
      assertEquals(5, historyService.createHistoricActivityInstanceQuery().activityId("undoBookFlight").count());
    }

    assertProcessEnded(processInstance.getId());
  }

  @Deployment
  public void testCompensationEndEventWithActivityRef() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    if (!processEngineConfiguration.getHistory().equals(ProcessEngineConfiguration.HISTORY_NONE)) {
      assertEquals(5, historyService.createHistoricActivityInstanceQuery().activityId("undoBookHotel").count());
      assertEquals(0, historyService.createHistoricActivityInstanceQuery().activityId("undoBookFlight").count());
    }

    assertProcessEnded(processInstance.getId());
  }

  @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.activityWithCompensationEndEvent.bpmn20.xml")
  public void testActivityInstanceTreeForCompensationEndEvent(){
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    ActivityInstance tree = runtimeService.getActivityInstance(processInstance.getId());
    assertThat(tree).hasStructure(
       describeActivityInstanceTree(processInstance.getProcessDefinitionId())
          .activity("end")
          .activity("undoBookHotel")
      .done());
  }

  @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.compensationMiActivity.bpmn20.xml")
  public void testActivityInstanceTreeForMiActivity(){
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    ActivityInstance tree = runtimeService.getActivityInstance(processInstance.getId());
    assertThat(tree).hasStructure(
       describeActivityInstanceTree(processInstance.getProcessDefinitionId())
          .activity("end")
          .beginMiBody("bookHotel")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
      .done());
  }

  @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensateParallelSubprocessCompHandlerWaitstate.bpmn20.xml")
  public void testActivityInstanceTreeForParallelMiActivityInSubprocess() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    ActivityInstance tree = runtimeService.getActivityInstance(processInstance.getId());
    assertThat(tree).hasStructure(
        describeActivityInstanceTree(processInstance.getProcessDefinitionId())
        .activity("parallelTask")
        .activity("throwCompensate")
          .beginScope("scope")
            .beginMiBody("bookHotel")
              .activity("undoBookHotel")
              .activity("undoBookHotel")
              .activity("undoBookHotel")
              .activity("undoBookHotel")
              .activity("undoBookHotel")
        .done());
  }

  @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.compensationMiSubprocess.bpmn20.xml")
  public void testActivityInstanceTreeForMiSubprocess(){
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("compensateProcess");

    completeTasks("Book Hotel", 5);
    // throw compensation event
    completeTask("throwCompensation");

    ActivityInstance tree = runtimeService.getActivityInstance(processInstance.getId());
    assertThat(tree).hasStructure(
       describeActivityInstanceTree(processInstance.getProcessDefinitionId())
          .activity("throwingCompensation")
          .beginMiBody("scope")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
            .activity("undoBookHotel")
      .done());
  }

  @Deployment(resources = { "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensationEventSubProcess.bpmn20.xml" })
  public void testCompensationEventSubProcessWithScope() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

    completeTask("Book Flight");
    completeTask("Book Hotel");

    // throw compensation event for current scope (without activityRef)
    completeTaskWithVariable("Validate Booking", "valid", false);

    // first - compensate book flight
    assertEquals(1, taskService.createTaskQuery().count());
    completeTask("Cancel Flight");
    // second - compensate book hotel
    assertEquals(1, taskService.createTaskQuery().count());
    completeTask("Cancel Hotel");
    // third - additional compensation handler
    completeTask("Update Customer Record");

    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensationEventSubProcessWithActivityRef() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

    completeTask("Book Hotel");
    completeTask("Book Flight");

    // throw compensation event for specific scope (with activityRef = subprocess)
    completeTaskWithVariable("Validate Booking", "valid", false);

    // compensate the activity within this scope
    assertEquals(1, taskService.createTaskQuery().count());
    completeTask("Cancel Hotel");

    assertProcessEnded(processInstanceId);
  }

  /**
   * enable test case when bug is fixed
   *
   * @see https://app.camunda.com/jira/browse/CAM-4285
   */
  @Deployment(resources = { "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensateEventTest.testCompensationEventSubProcess.bpmn20.xml" })
  public void FAILING_testActivityInstanceTreeForCompensationEventSubProcess() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("bookingProcess");

    completeTask("Book Flight");
    completeTask("Book Hotel");

    // throw compensation event
    completeTaskWithVariable("Validate Booking", "valid", false);

    ActivityInstance tree = runtimeService.getActivityInstance(processInstance.getId());
    assertThat(tree).hasStructure(
          describeActivityInstanceTree(processInstance.getProcessDefinitionId())
            .activity("throwCompensation")
            .beginScope("booking-subprocess")
              .beginScope("compensationSubProcess")
                .activity("compensateFlight")
                .activity("cancelFlight")
         .done());
  }

  @Deployment
  public void testCompensateMiSubprocessWithCompensationEventSubProcess() {
    Map<String, Object> variables = new HashMap<String, Object>();
    // multi instance collection
    variables.put("flights", Arrays.asList("STS-14", "STS-28"));

    String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess", variables).getId();

    completeTask("Book Flight");
    completeTask("Book Hotel");

    completeTask("Book Flight");
    completeTask("Book Hotel");

    // throw compensation event
    completeTaskWithVariable("Validate Booking", "valid", false);

    // execute compensation handlers for each execution of the subprocess
    completeTasks("Cancel Flight", 2);
    completeTasks("Cancel Hotel", 2);
    completeTasks("Update Customer Record", 2);

    assertProcessEnded(processInstanceId);
  }

  /**
   * enable test case when bug is fixed
   *
   * @see https://app.camunda.com/jira/browse/CAM-4284
   */
  @Deployment
  public void FAILING_testCompensateParallelMiSubprocessWithCompensationEventSubProcess() {
    Map<String, Object> variables = new HashMap<String, Object>();
    // multi instance collection
    variables.put("flights", Arrays.asList("STS-14", "STS-28"));

    String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess", variables).getId();

    completeTasks("Book Flight", 2);
    completeTasks("Book Hotel", 2);

    // throw compensation event
    completeTaskWithVariable("Validate Booking", "valid", false);

    // execute compensation handlers for each execution of the subprocess
    completeTasks("Cancel Flight", 2);
    completeTasks("Cancel Hotel", 2);
    completeTasks("Update Customer Record", 2);

    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensationEventSubprocessWithoutBoundaryEvents() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    completeTask("Book Hotel");
    completeTask("Book Flight");

    // throw compensation event
    completeTask("throw compensation");

    // execute compensation handlers
    completeTask("Cancel Flight");
    completeTask("Cancel Hotel");

    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensationEventSubprocessReThrowCompensationEvent() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    completeTask("Book Hotel");
    completeTask("Book Flight");

    // throw compensation event
    completeTask("throw compensation");

    // execute compensation handler and re-throw compensation event
    completeTask("Cancel Hotel");
    // execute compensation handler at subprocess
    completeTask("Cancel Flight");

    assertProcessEnded(processInstanceId);
  }

  @Deployment
  public void testCompensationEventSubprocessConsumeCompensationEvent() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("compensateProcess").getId();

    completeTask("Book Hotel");
    completeTask("Book Flight");

    // throw compensation event
    completeTask("throw compensation");

    // execute compensation handler and consume compensation event
    completeTask("Cancel Hotel");
    // compensation handler at subprocess (Cancel Flight) should not be executed
    assertProcessEnded(processInstanceId);
  }

  private void completeTask(String taskName) {
    completeTasks(taskName, 1);
  }

  private void completeTasks(String taskName, int times) {
    List<Task> tasks = taskService.createTaskQuery().taskName(taskName).list();

    assertTrue("Actual there are " + tasks.size() + " open tasks with name '" + taskName + "'. Expected at least " + times, times <= tasks.size());

    Iterator<Task> taskIterator = tasks.iterator();
    for (int i = 0; i < times; i++) {
      Task task = taskIterator.next();
      taskService.complete(task.getId());
    }
  }

  private void completeTaskWithVariable(String taskName, String variable, Object value) {
    Task task = taskService.createTaskQuery().taskName(taskName).singleResult();
    assertNotNull("No open task with name '" + taskName + "'", task);

    Map<String, Object> variables = new HashMap<String, Object>();
    if (variable != null) {
      variables.put(variable, value);
    }

    taskService.complete(task.getId(), variables);
  }

}
