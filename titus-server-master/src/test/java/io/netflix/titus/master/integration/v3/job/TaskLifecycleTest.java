/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netflix.titus.master.integration.v3.job;

import com.netflix.titus.grpc.protogen.TaskStatus;
import io.netflix.titus.api.jobmanager.model.job.JobDescriptor;
import io.netflix.titus.api.jobmanager.model.job.JobGroupInfo;
import io.netflix.titus.api.jobmanager.model.job.ext.BatchJobExt;
import io.netflix.titus.api.jobmanager.model.job.ext.ServiceJobExt;
import io.netflix.titus.master.integration.v3.scenario.InstanceGroupsScenarioBuilder;
import io.netflix.titus.master.integration.v3.scenario.JobsScenarioBuilder;
import io.netflix.titus.master.integration.v3.scenario.TaskScenarioBuilder;
import io.netflix.titus.testkit.junit.category.IntegrationTest;
import io.netflix.titus.testkit.junit.master.TitusStackResource;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;

import static io.netflix.titus.master.integration.v3.scenario.InstanceGroupScenarioTemplates.basicSetupActivation;
import static io.netflix.titus.master.integration.v3.scenario.ScenarioTemplates.jobAccepted;
import static io.netflix.titus.master.integration.v3.scenario.ScenarioTemplates.lockTaskInState;
import static io.netflix.titus.master.integration.v3.scenario.ScenarioTemplates.startJobAndMoveToKillInitiated;
import static io.netflix.titus.testkit.embedded.stack.EmbeddedTitusStacks.basicStack;
import static io.netflix.titus.testkit.junit.master.TitusStackResource.V3_ENGINE_APP_PREFIX;
import static io.netflix.titus.testkit.model.job.JobDescriptorGenerator.oneTaskBatchJobDescriptor;
import static io.netflix.titus.testkit.model.job.JobDescriptorGenerator.oneTaskServiceJobDescriptor;

@Category(IntegrationTest.class)
public class TaskLifecycleTest {

    private static final JobDescriptor<BatchJobExt> ONE_TASK_BATCH_JOB = oneTaskBatchJobDescriptor().toBuilder().withApplicationName(V3_ENGINE_APP_PREFIX).build();

    private static final TitusStackResource titusStackResource = new TitusStackResource(basicStack(2).toMaster(master -> master
            .withProperty("titusMaster.jobManager.taskInLaunchedStateTimeoutMs", "2000")
            .withProperty("titusMaster.jobManager.batchTaskInStartInitiatedStateTimeoutMs", "2000")
            .withProperty("titusMaster.jobManager.serviceTaskInStartInitiatedStateTimeoutMs", "2000")
            .withProperty("titusMaster.jobManager.taskInKillInitiatedStateTimeoutMs", "100")
    ));

    private static final JobsScenarioBuilder jobsScenarioBuilder = new JobsScenarioBuilder(titusStackResource);

    private static final InstanceGroupsScenarioBuilder instanceGroupsScenarioBuilder = new InstanceGroupsScenarioBuilder(titusStackResource);

    @ClassRule
    public static final RuleChain ruleChain = RuleChain.outerRule(titusStackResource).around(instanceGroupsScenarioBuilder).around(jobsScenarioBuilder);

    @BeforeClass
    public static void setUp() throws Exception {
        instanceGroupsScenarioBuilder.synchronizeWithCloud().template(basicSetupActivation());
    }

    @Test(timeout = 30_000)
    public void submitBatchJobStuckInLaunched() throws Exception {
        testTaskStuckInState(ONE_TASK_BATCH_JOB, TaskStatus.TaskState.Launched);
    }

    @Test(timeout = 30_000)
    public void submitBatchJobStuckInStartInitiated() throws Exception {
        testTaskStuckInState(ONE_TASK_BATCH_JOB, TaskStatus.TaskState.StartInitiated);
    }

    @Test(timeout = 30_000)
    public void submitBatchJobStuckInKillInitiated() throws Exception {
        jobsScenarioBuilder.schedule(ONE_TASK_BATCH_JOB, jobScenarioBuilder -> jobScenarioBuilder
                .template(startJobAndMoveToKillInitiated(true))
                .expectJobEventStreamCompletes()
        );
    }

    @Test(timeout = 30_000)
    public void submitServiceJobStuckInLaunched() throws Exception {
        testTaskStuckInState(newJob("submitServiceJobStuckInLaunched"), TaskStatus.TaskState.Launched);
    }

    @Test(timeout = 30_000)
    public void submitServiceJobStuckInStartInitiated() throws Exception {
        testTaskStuckInState(newJob("submitServiceJobStuckInStartInitiated"), TaskStatus.TaskState.StartInitiated);
    }

    @Test(timeout = 30_000)
    public void submitServiceJobStuckInKillInitiated() throws Exception {
        jobsScenarioBuilder.schedule(newJob("submitServiceJobStuckInKillInitiated"), jobScenarioBuilder -> jobScenarioBuilder
                .template(startJobAndMoveToKillInitiated(true))
                .expectJobEventStreamCompletes()
        );
    }

    private void testTaskStuckInState(JobDescriptor<?> jobDescriptor, TaskStatus.TaskState state) throws Exception {
        jobsScenarioBuilder.schedule(jobDescriptor, jobScenarioBuilder -> jobScenarioBuilder
                .template(jobAccepted())
                .expectAllTasksCreated()
                .allTasks(TaskScenarioBuilder::expectTaskOnAgent)
                .inTask(0, taskScenarioBuilder -> taskScenarioBuilder.template(lockTaskInState(state)))
                .expectJobEventStreamCompletes()
        );
    }

    private JobDescriptor<ServiceJobExt> newJob(String detail) {
        return oneTaskServiceJobDescriptor().toBuilder()
                .withApplicationName(TitusStackResource.V3_ENGINE_APP_PREFIX)
                .withJobGroupInfo(JobGroupInfo.newBuilder().withDetail(detail).build())
                .build();
    }
}
