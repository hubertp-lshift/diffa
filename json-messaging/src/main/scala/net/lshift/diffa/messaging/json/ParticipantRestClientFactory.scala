/**
 * Copyright (C) 2010-2011 LShift Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lshift.diffa.messaging.json

import net.lshift.diffa.kernel.participants._

trait ParticipantRestClientFactory {

  def supportsAddress(address: String, protocol: String) =
    protocol == "application/json" && address.startsWith("http://")
}

class ScanningParticipantRestClientFactory
  extends ScanningParticipantFactory with ParticipantRestClientFactory {

  def createParticipantRef(address: String, protocol: String) = new ScanningParticipantRestClient(address)
}

class ContentParticipantRestClientFactory
  extends ContentParticipantFactory with ParticipantRestClientFactory {

  def createParticipantRef(address: String, protocol: String) = new ContentParticipantRestClient(address)
}

class VersioningParticipantRestClientFactory
  extends VersioningParticipantFactory with ParticipantRestClientFactory {

  def createParticipantRef(address: String, protocol: String) = new VersioningParticipantRestClient(address)
}