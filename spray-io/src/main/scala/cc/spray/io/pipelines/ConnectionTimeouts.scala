/*
 * Copyright (C) 2011, 2012 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.io
package pipelines

import akka.util.Duration
import akka.event.LoggingAdapter

object ConnectionTimeouts {

  def apply(config: ConnectionTimeoutConfig, log: LoggingAdapter): PipelineStage =
    if (config.enableConnectionTimeouts) createPipelineStage(config, log) else EmptyPipelineStage

  def createPipelineStage(config: ConnectionTimeoutConfig, log: LoggingAdapter) = new DoublePipelineStage {
    def build(context: PipelineContext, commandPL: Pipeline[Command], eventPL: Pipeline[Event]) = new Pipelines {
      val reapingTrigger = context.connectionActorContext.system.scheduler.schedule(
        initialDelay = config.reapingCycle,
        frequency = config.reapingCycle,
        receiver = context.connectionActorContext.self,
        message = ReapIdleConnections
      )
      var idleTimeout = config.idleTimeout
      var lastActivity = System.currentTimeMillis

      def commandPipeline(command: Command) {
        command match {
          case x: SetIdleTimeout => idleTimeout = x.timeout
          case _ => commandPL(command)
        }
      }

      def eventPipeline(event: Event) {
        event match {
          case _: IoPeer.Received =>
            lastActivity = System.currentTimeMillis
            eventPL(event)
          case _: IoPeer.SendCompleted =>
            lastActivity = System.currentTimeMillis
            eventPL(event)
          case _: IoPeer.Closed =>
            reapingTrigger.cancel()
            eventPL(event)
          case ReapIdleConnections =>
            if (idleTimeout.isFinite && (lastActivity + idleTimeout.toMillis) < System.currentTimeMillis) {
              log.debug("Closing connection due to idle timeout...")
              commandPL(IoPeer.Close(IdleTimeout))
            }
          case _ => eventPL(event)
        }
      }
    }
  }

  ////////////// COMMANDS //////////////
  case class SetIdleTimeout(timeout: Duration) extends Command

  ////////////// EVENTS //////////////
  case object ReapIdleConnections extends Event
}

trait ConnectionTimeoutConfig {
  def enableConnectionTimeouts: Boolean
  def idleTimeout: Duration
  def reapingCycle: Duration
}

object ConnectionTimeoutConfig {
  val defaultEnableConnectionTimeouts = true
  val defaultIdleTimeout = Duration("10 sec")
  val defaultReapingCycle = Duration("10 ms")
}