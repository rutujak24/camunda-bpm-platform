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
package org.camunda.bpm.engine.test.api.multitenancy.query.history;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.batch.history.HistoricBatch;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.api.runtime.migration.batch.BatchMigrationHelper;
import org.camunda.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/**
 * @author Thorben Lindhauer
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class MultiTenancyHistoricBatchQueryTest {

  protected static final String TENANT_ONE = "tenant1";
  protected static final String TENANT_TWO = "tenant2";

  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain defaultRuleChin = RuleChain.outerRule(engineRule).around(testHelper);

  protected BatchMigrationHelper batchHelper = new BatchMigrationHelper(engineRule);

  protected ManagementService managementService;
  protected RuntimeService runtimeService;
  protected HistoryService historyService;
  protected IdentityService identityService;

  protected Batch sharedBatch;
  protected Batch tenant1Batch;
  protected Batch tenant2Batch;

  @Before
  public void initServices() {
    managementService= engineRule.getManagementService();
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();
    identityService = engineRule.getIdentityService();
  }

  @Before
  public void deployProcesses() {
    ProcessDefinition sharedDefinition = testHelper.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition tenant1Definition = testHelper.deployForTenantAndGetDefinition(TENANT_ONE, ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition tenant2Definition = testHelper.deployForTenantAndGetDefinition(TENANT_TWO, ProcessModels.ONE_TASK_PROCESS);

    sharedBatch = createInstanceAndStartBatchMigration(sharedDefinition);
    tenant1Batch = createInstanceAndStartBatchMigration(tenant1Definition);
    tenant2Batch = createInstanceAndStartBatchMigration(tenant2Definition);
  }

  @After
  public void removeBatches() {
    HistoryService historyService = engineRule.getHistoryService();

    for (Batch batch : managementService.createBatchQuery().list()) {
      managementService.deleteBatch(batch.getId(), true);
    }

    // remove history of completed batches
    for (HistoricBatch historicBatch : historyService.createHistoricBatchQuery().list()) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }
  }

  @Test
  public void testHistoricBatchQueryNoAuthenticatedTenant() {
    // given
    identityService.setAuthentication("user", null, null);

    // when
    List<HistoricBatch> batches = historyService.createHistoricBatchQuery().list();

    // then
    Assert.assertEquals(1, batches.size());
    Assert.assertEquals(sharedBatch.getId(), batches.get(0).getId());

    Assert.assertEquals(1, historyService.createHistoricBatchQuery().count());

    identityService.clearAuthentication();
  }

  @Test
  public void testHistoricBatchQueryAuthenticatedTenant() {
    // given
    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    // when
    List<HistoricBatch> batches = historyService.createHistoricBatchQuery().list();

    // then
    Assert.assertEquals(2, batches.size());
    assertBatches(batches, tenant1Batch.getId(), sharedBatch.getId());

    Assert.assertEquals(2, historyService.createHistoricBatchQuery().count());

    identityService.clearAuthentication();
  }

  @Test
  public void testHistoricBatchQueryAuthenticatedTenants() {
    // given
    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE, TENANT_TWO));

    // when
    List<HistoricBatch> batches = historyService.createHistoricBatchQuery().list();

    // then
    Assert.assertEquals(3, batches.size());

    Assert.assertEquals(3, historyService.createHistoricBatchQuery().count());

    identityService.clearAuthentication();
  }

  @Test
  public void testDeleteHistoricBatch() {
    // given
    Batch tenant1Batch = createInstanceAndStartBatchMigration(tenant1Definition);

    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    // when
    historyService.deleteHistoricBatch(tenant1Batch.getId());

    // then
    identityService.clearAuthentication();
    Assert.assertEquals(0, historyService.createHistoricBatchQuery().count());
  }

  @Test
  public void testDeleteHistoricBatchFailsWithWrongTenant() {
    // given
    Batch tenant2Batch = createInstanceAndStartBatchMigration(tenant2Definition);

    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    // when
    try {
      historyService.deleteHistoricBatch(tenant2Batch.getId());
      Assert.fail("exception expected");
    }
    catch (ProcessEngineException e) {
      // then
      Assert.assertThat(e.getMessage(), CoreMatchers.containsString("Cannot delete historic batch because it belongs to no authenticated tenant"));
    }

    identityService.clearAuthentication();
  }

  protected Batch createInstanceAndStartBatchMigration(ProcessDefinition processDefinition) {
    ProcessInstance processInstance = engineRule.getRuntimeService().startProcessInstanceById(processDefinition.getId());

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(processDefinition.getId(), processDefinition.getId())
      .mapEqualActivities()
      .build();

    Batch batch = engineRule.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .executeAsync();

    return batch;
  }

  protected void assertBatches(List<HistoricBatch> actualBatches, String... expectedIds) {
    Assert.assertEquals(expectedIds.length, actualBatches.size());

    Set<String> actualIds = new HashSet<String>();
    for (HistoricBatch batch : actualBatches) {
      actualIds.add(batch.getId());
    }

    for (String expectedId : expectedIds) {
      Assert.assertTrue(actualIds.contains(expectedId));
    }
  }
}
