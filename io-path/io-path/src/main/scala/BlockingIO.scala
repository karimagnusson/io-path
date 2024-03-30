/*
* Copyright 2021 Kári Magnússon
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

package io.github.karimagnusson.io.path

import scala.concurrent.{Future, ExecutionContext}
import org.apache.pekko.actor.ActorSystem


trait BlockingIO {
  val system: ActorSystem
  val ec: ExecutionContext
  val defaultEc: ExecutionContext
  def run[T](fn: => T): Future[T]
}


object BlockingIO {
  def apply(system: ActorSystem): BlockingIO =
    DefaultBlockingIO(system)
}


case class DefaultBlockingIO(system: ActorSystem) extends BlockingIO {

  val defaultEc: ExecutionContext = system.dispatcher

  val ec: ExecutionContext = system.dispatchers.lookup(
    "pekko.actor.default-blocking-io-dispatcher"
  )

  def run[T](fn: => T): Future[T] = Future(fn)(ec)
}