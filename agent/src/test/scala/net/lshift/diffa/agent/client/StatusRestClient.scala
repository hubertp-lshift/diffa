/**
 * Copyright (C) 2012 LShift Ltd.
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
package net.lshift.diffa.agent.client


import com.sun.jersey.api.client.ClientResponse
import net.lshift.diffa.client.{RestClientParams, AbstractRestClient}
import java.lang.String


class StatusRestClient(rootUrl:String)
    extends AbstractRestClient(rootUrl, "rest/status", RestClientParams()) {

  def checkStatus = {
    val response = resource.get(classOf[ClientResponse])
    response.getClientResponseStatus.getStatusCode match {
      case 200 => true
      case _   => false
    }
  }
}