/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package org.gradle.composite.internal


import org.gradle.api.internal.BuildDefinition
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.artifacts.DefaultBuildIdentifier
import org.gradle.api.internal.project.ProjectStateRegistry
import org.gradle.execution.MultipleBuildFailures
import org.gradle.initialization.GradleLauncher
import org.gradle.initialization.GradleLauncherFactory
import org.gradle.initialization.RootBuildLifecycleListener
import org.gradle.initialization.exception.ExceptionAnalyser
import org.gradle.internal.buildtree.BuildTreeState
import org.gradle.internal.event.ListenerManager
import org.gradle.internal.invocation.BuildController
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.service.ServiceRegistry
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.test.fixtures.work.TestWorkerLeaseService
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Function

class DefaultRootBuildStateTest extends Specification {
    def factory = Mock(GradleLauncherFactory)
    def launcher = Mock(GradleLauncher)
    def gradle = Mock(GradleInternal)
    def listenerManager = Mock(ListenerManager)
    def lifecycleListener = Mock(RootBuildLifecycleListener)
    def action = Mock(Function)
    def buildTree = Mock(BuildTreeState)
    def sessionServices = Mock(ServiceRegistry)
    def buildDefinition = Mock(BuildDefinition)
    def projectStateRegistry = Mock(ProjectStateRegistry)
    def includedBuildControllers = Mock(IncludedBuildControllers)
    def exceptionAnalyzser = Mock(ExceptionAnalyser)
    DefaultRootBuildState build

    def setup() {
        _ * factory.newInstance(buildDefinition, _, buildTree) >> launcher
        _ * listenerManager.getBroadcaster(RootBuildLifecycleListener) >> lifecycleListener
        _ * sessionServices.get(ProjectStateRegistry) >> projectStateRegistry
        _ * sessionServices.get(BuildOperationExecutor) >> Stub(BuildOperationExecutor)
        _ * sessionServices.get(WorkerLeaseService) >> new TestWorkerLeaseService()
        _ * sessionServices.get(IncludedBuildControllers) >> includedBuildControllers
        _ * sessionServices.get(ExceptionAnalyser) >> exceptionAnalyzser
        _ * launcher.gradle >> gradle
        _ * gradle.services >> sessionServices
        _ * projectStateRegistry.withLenientState(_) >> { args -> return args[0].create() }

        build = new DefaultRootBuildState(buildDefinition, factory, listenerManager, buildTree)
    }

    def "has identifier"() {
        expect:
        build.buildIdentifier == DefaultBuildIdentifier.ROOT
    }

    def "stops launcher on stop"() {
        when:
        build.stop()

        then:
        1 * launcher.stop()
    }

    def "runs action after notifying listeners"() {
        when:
        def result = build.run(action)

        then:
        result == '<result>'

        then:
        1 * lifecycleListener.afterStart(_ as GradleInternal)

        then:
        1 * action.apply(!null) >> { BuildController controller ->
            '<result>'
        }

        then:
        1 * lifecycleListener.beforeComplete(_ as GradleInternal)
    }

    def "can have null result"() {
        when:
        def result = build.run(action)

        then:
        result == null

        and:
        1 * action.apply(!null) >> { BuildController controller ->
            return null
        }
    }

    def "does not finish build when action does not request anything"() {
        when:
        def result = build.run(action)

        then:
        result == '<result>'

        then:
        1 * action.apply(!null) >> { BuildController controller ->
            '<result>'
        }
        0 * launcher._
        0 * includedBuildControllers._
    }

    def "runs tasks and finishes build when requested by action"() {
        when:
        def result = build.run(action)

        then:
        result == '<result>'

        and:
        1 * launcher.executeTasks() >> gradle
        1 * action.apply(!null) >> { BuildController controller ->
            controller.run()
            return '<result>'
        }
        1 * launcher.finishBuild(_)
        1 * includedBuildControllers.finishBuild(_)
    }

    def "configures and finishes build when requested by action"() {
        when:
        def result = build.run(action)

        then:
        result == '<result>'

        and:
        1 * launcher.getConfiguredBuild() >> gradle
        1 * action.apply(!null) >> { BuildController controller ->
            controller.configure()
            return '<result>'
        }
        1 * launcher.finishBuild(_)
        1 * includedBuildControllers.finishBuild(_)
    }

    def "cannot request configuration after build has been run"() {
        given:
        action.apply(!null) >> { BuildController controller ->
            controller.run()
            controller.configure()
        }

        when:
        build.run(action)

        then:
        IllegalStateException e = thrown()
        e.message == 'Cannot use launcher after build has completed.'

        and:
        1 * launcher.executeTasks() >> gradle
    }

    def "forwards action failure and cleans up"() {
        def failure = new RuntimeException()

        when:
        build.run(action)

        then:
        RuntimeException e = thrown()
        e == failure

        and:
        1 * action.apply(!null) >> { BuildController controller -> throw failure }
        1 * lifecycleListener.beforeComplete(_ as GradleInternal)
    }

    def "forwards build failure and cleans up"() {
        def failure = new RuntimeException()
        def transformedFailure = new RuntimeException()

        when:
        build.run(action)

        then:
        RuntimeException e = thrown()
        e == transformedFailure

        and:
        1 * launcher.executeTasks() >> { throw failure }
        1 * action.apply(!null) >> { BuildController controller ->
            controller.run()
        }
        1 * includedBuildControllers.finishBuild(_)
        1 * launcher.finishBuild(_)
        1 * exceptionAnalyzser.transform(_) >> { MultipleBuildFailures ex ->
            assert ex.causes == [failure]
            throw transformedFailure
        }
        1 * lifecycleListener.beforeComplete(_ as GradleInternal)
    }

    def "forwards configure failure and cleans up"() {
        def failure = new RuntimeException()
        def transformedFailure = new RuntimeException()

        when:
        build.run(action)

        then:
        RuntimeException e = thrown()
        e == transformedFailure

        and:
        1 * launcher.getConfiguredBuild() >> { throw failure }
        1 * action.apply(!null) >> { BuildController controller ->
            controller.configure()
        }
        1 * includedBuildControllers.finishBuild(_)
        1 * launcher.finishBuild(_)
        1 * exceptionAnalyzser.transform(_) >> { MultipleBuildFailures ex ->
            assert ex.causes == [failure]
            throw transformedFailure
        }
        1 * lifecycleListener.beforeComplete(_ as GradleInternal)
    }

    def "collects and transforms build finish failures"() {
        def failure1 = new RuntimeException()
        def failure2 = new RuntimeException()
        def failure3 = new RuntimeException()
        def transformedFailure = new RuntimeException()

        when:
        build.run(action)

        then:
        RuntimeException e = thrown()
        e == transformedFailure

        and:
        1 * launcher.executeTasks() >> { throw failure1 }
        1 * action.apply(!null) >> { BuildController controller ->
            controller.run()
        }
        1 * includedBuildControllers.finishBuild(_) >> { Consumer consumer -> consumer.accept(failure2) }
        1 * launcher.finishBuild(_) >> { Consumer consumer -> consumer.accept(failure3) }
        1 * exceptionAnalyzser.transform(_) >> { MultipleBuildFailures ex ->
            assert ex.causes == [failure1, failure2, failure3]
            throw transformedFailure
        }
        1 * lifecycleListener.beforeComplete(_ as GradleInternal)
    }

    def "cannot run after configuration failure"() {
        when:
        build.run(action)

        then:
        IllegalStateException e = thrown()
        e.message == 'Cannot use launcher after build has completed.'

        and:
        1 * launcher.configuredBuild >> { throw new RuntimeException() }
        1 * action.apply(!null) >> { BuildController controller ->
            try {
                controller.configure()
            } catch (RuntimeException) {
                // Ignore
            }
            controller.run()
        }
        1 * lifecycleListener.beforeComplete(_ as GradleInternal)
    }
}
