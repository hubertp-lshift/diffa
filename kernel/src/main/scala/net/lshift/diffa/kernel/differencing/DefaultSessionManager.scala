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

package net.lshift.diffa.kernel.differencing

import collection.mutable.{ListBuffer, HashMap}
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.{Logger, LoggerFactory}
import net.lshift.diffa.kernel.matching.{MatchingManager, MatchingStatusListener}
import net.lshift.diffa.kernel.actors.PairPolicyClient
import net.lshift.diffa.kernel.participants._
import net.lshift.diffa.kernel.events.VersionID
import org.joda.time.{Interval, DateTime}
import net.lshift.diffa.kernel.util.MissingObjectException
import net.lshift.diffa.kernel.lifecycle.{NotificationCentre, AgentLifecycleAware}
import net.lshift.diffa.kernel.config.system.SystemConfigStore
import net.lshift.diffa.kernel.config.{DiffaPairRef, Endpoint, DomainConfigStore, Pair => DiffaPair}

/**
 * Standard implementation of the SessionManager.
 *
 * Terminology:
 *  - Pending events are events that have resulted in differences, but the matching manager is still waiting for a
 *     timeout on;
 *  - Reportable events are events that have resulted in differences, and the matching manager has expired its window for it;
 *
 * Events sent to clients all have sequence identifiers, allowing clients to incrementally update. Internally, the
 * session manager will not allocate a sequence number for an event until an event goes reportable, since many events
 * are likely to be generated internally in normal flows that will never be shown externally (eg, a message sent from
 * A -> B will likely be marked as mismatched by the differencing engine for a short period of time, but be suppressed
 * whilst the matching manager waits for it to expire).
 */
class DefaultSessionManager(
        val systemConfig:SystemConfigStore,
        val cacheProvider:SessionCacheProvider,
        val matching:MatchingManager,
        val vpm:VersionPolicyManager,
        val pairPolicyClient:PairPolicyClient,
        val participantFactory:ParticipantFactory)
    extends SessionManager
    with DifferencingListener with MatchingStatusListener with PairScanListener with AgentLifecycleAware {

  private val log:Logger = LoggerFactory.getLogger(getClass)

  private val listeners = new HashMap[String, ListBuffer[DifferencingListener]]

  /**
   * This is a map of every open session (keyed on session id) keyed on the pairing it is linked to
   */
  private val sessionsByKey = new HashMap[String, SessionCache]

  private val participants = new HashMap[Endpoint, Participant]

  private val pairStates = new HashMap[DiffaPair, PairScanState]

  // Subscribe to events from the matching manager
  matching.addListener(this)

  //
  // SessionManager Implementation
  //

  def start(scope:SessionScope, start:DateTime, end:DateTime) = {

    def boot() = {
      val sessionId = generateSessionId(scope, start, end)

      // Only create a session if don't already have one for this key
      val sessionToInit = sessionsByKey.synchronized {
        sessionsByKey.get(sessionId) match {
          case Some(s) => None
          case None => {
            val session = cacheProvider.retrieveOrAllocateCache(sessionId, scope)
            sessionsByKey(sessionId) = session
            Some(session)
          }
        }
      }

      // If a session to initialize was return, we should do that.
      sessionToInit match {
        case Some(session) => {
          runDifferenceForScope(scope, start, end)
        }
        case None => // Do nothing
      }

      log.debug("Created session <" + sessionId + "> for the scope " + scope)
      sessionId
    }
    
    withValidScope(scope, boot)
  }



  def start(scope: SessionScope, sessionStart:DateTime, sessionEnd:DateTime, listener: DifferencingListener) : String = {
    val sessionId = start(scope, sessionStart, sessionEnd)
    listeners.synchronized {
      val keyListeners = listeners.get(sessionId) match {
        case Some(ll) => ll
        case None => {
          val newListeners = new ListBuffer[DifferencingListener]
          listeners(sessionId) = newListeners
          newListeners
        }
      }
      keyListeners += listener
    }
    runDifferenceForScope(scope, sessionStart, sessionEnd)
    sessionId
  }


  def end(sessionID: String) = {
    sessionsByKey.contains(sessionID) match {
      case false => ()
      case true  => sessionsByKey.remove(sessionID)
    }
  }

  // TODO: This should be using a SessionID, and not a pair
  def end(pair: DiffaPair, listener: DifferencingListener) = {
    forEachSession(VersionID(pair.asRef, "dummy"), s => {
      listeners.synchronized {
        listeners.get(s.sessionId) match {
          case None =>
          case Some(keyListeners) => {
            keyListeners -= listener
          }
        }
      }
    })
  }

  def retrievePairScanStates(sessionID: String) = {
    // Gather the states for all pairs in the given session. Since some session
    sessionsByKey.get(sessionID) match {
      case Some(cache) => pairScanStates(cache.scope)
      case None        => Map()     // No pairs in an inactive session
    }
  }

  def retrieveAllPairScanStates = pairScanStates(SessionScope.all)

  def pairScanStates(scope:SessionScope) = {
    val pairs = pairsForScope(scope)
    pairStates.synchronized {
      pairs.map(p => p.key -> pairStates.getOrElse(p, PairScanState.UNKNOWN)).toMap
    }
  }

  def runScanForAllPairings() {
    runScanForScope(SessionScope.all, null, null)
  }

  def runScan(sessionID:String) = {
    sessionsByKey.get(sessionID) match {
      case None => // No session. Nothing to do. TODO: Throw an exception?
      case Some(cache) => {
        runScanForScope(cache.scope, null, null)
      }
    }
  }

  /**
   * If the session does not exist, throw a MissingObjectException which will be handled in a higher layer
   */
  def safeGetSession(id:String) = {
    sessionsByKey.get(id) match {
      case Some(s) => s
      case None    => {
        if (log.isTraceEnabled) {
          log.trace("Request for non-existent session: %s".format(id))
        }
        throw new MissingObjectException(id)
      }
    }
  }

  def retrieveSessionVersion(id:String) = safeGetSession(id).currentVersion

  def retrieveAllEventsInInterval(sessionId:String, interval:Interval) =
    sessionsByKey(sessionId).retrieveUnmatchedEvents(interval)

  def retrievePagedEvents(sessionId:String, pairKey:String, interval:Interval, offset:Int, length:Int) =
    sessionsByKey(sessionId).retrievePagedEvents(pairKey, interval, offset, length)

  def countEvents(sessionId: String, pairKey: String, interval: Interval) =
    sessionsByKey(sessionId).countEvents(pairKey, interval)

  def retrieveEventDetail(sessionID:String, evtSeqId:String, t: ParticipantType.ParticipantType) = {
    log.trace("Requested a detail query for session (" + sessionID + ") and seq (" + evtSeqId + ") and type (" + t + ")")
    t match {
      case ParticipantType.UPSTREAM => {
        withValidEvent(sessionID, evtSeqId,
                      {e:SessionEvent => e.upstreamVsn != null},
                      {p:net.lshift.diffa.kernel.config.Pair => p.upstream},
                      {e:Endpoint => participantFactory.createUpstreamParticipant(e)})
      }
      case ParticipantType.DOWNSTREAM => {
        withValidEvent(sessionID, evtSeqId,
                      {e:SessionEvent => e.downstreamVsn != null},
                      {p:net.lshift.diffa.kernel.config.Pair => p.downstream},
                      {e:Endpoint => participantFactory.createDownstreamParticipant(e)})
      }
    }
  }

  // TODO The fact that 3 lambdas are passed in probably indicates bad factoring
  // -> the participant factory call is probably low hanging fruit for refactoring
  def withValidEvent(sessionID:String, evtSeqId:String,
                     check:Function1[SessionEvent,Boolean],
                     resolve:(net.lshift.diffa.kernel.config.Pair) => Endpoint,
                     p:(Endpoint) => Participant): String = {
    val event = safeGetSession(sessionID).getEvent(evtSeqId)
    check(event) match {
      case true  => {
       val id = event.objId
       val pair = systemConfig.getPair(id.pair.domain, id.pair.key)
       val endpoint = resolve(pair)
       if (!participants.contains(endpoint)) {
         participants(endpoint) = p(endpoint)
       }
       val participant = participants(endpoint)
       participant.retrieveContent(id.id)
      }
      case false => "Expanded detail not available"
    }

  }

  //
  // Lifecycle Management
  //

  override def onAgentInstantiationCompleted(nc: NotificationCentre) {
    nc.registerForDifferenceEvents(this)
    nc.registerForPairScanEvents(this)
  }

  //
  // Differencing Input
  //

  /**
   * This is the callback that channels mismatch events from the version policy into the session cache.
   *
   * Queries the matching manager to see if it is actively monitoring this VersionID (ie, it has unexpired events around it).
   * If yes -> just record it as a pending event. Don't tell clients anything yet.
   * If no -> this is a reportable event. Record it in the active list, and emit an event to our clients.
   */
  def onMismatch(id: VersionID, lastUpdate:DateTime, upstreamVsn: String, downstreamVsn: String, origin:MatchOrigin) = {
    log.trace("Processing mismatch for " + id + " with upstreamVsn '" + upstreamVsn + "' and downstreamVsn '" + downstreamVsn + "'")

    val pair = systemConfig.getPair(id.pair.domain, id.pair.key)

    matching.getMatcher(pair) match {
      case Some(matcher) => {
        matcher.isVersionIDActive(id) match {
          case true  => reportPending(id, lastUpdate, upstreamVsn, downstreamVsn)
          case false => reportUnmatched(id, lastUpdate, upstreamVsn, downstreamVsn)
        }
      }
      case None    => {
        // If no matcher is configured, then report mis-matches immediately
        reportUnmatched(id, lastUpdate, upstreamVsn, downstreamVsn)
      }
    }
  }

  /**
   * This is the callback that channels match events from the version policy into the session cache.
   * If the ID is currently in our list of reportable events, generate a match event to reverse it,
   * and end the reportable unmatched event.
   * If the ID is current in our list of pending events, then just end the id from our list of events.
   * If we don't know about this id (no mismatches for this id reported), just ignore.
   */
  def onMatch(id: VersionID, vsn: String, origin:MatchOrigin) {
    log.debug("Processing match for " + id + " with vsn '" + vsn + "'")

    // Rest API
    addMatched(id, vsn)

    // Streaming API
    forEachSession(id, s => forEachSessionListener(s, l => l.onMatch(id, vsn, LiveWindow)))
  }
  
  //
  // Matching Status Input
  //

  def onDownstreamExpired(id: VersionID, vsn: String) = upgradePending(id)  
  def onUpstreamExpired(id: VersionID, vsn: String) = upgradePending(id)

  /**
   * This event is unimportant from the perspective of maintaining the session, hence just drop it 
   */
  def onPaired(id: VersionID, vsn: String) = ()


  //
  // Pair Scan Notifications
  //

  def pairScanStateChanged(pair: DiffaPair, scanState: PairScanState) = updatePairScanState(pair, scanState)


  //
  // Configuration Change Notifications
  //



  // Internal plumbing

  /**
   * When pairs are updated, perform a differencing run to scan with their status.
   */
  def onUpdatePair(pair: DiffaPair) = {
    val isRelevantToASession =
      sessionsByKey.values.foldLeft(false)((currentlyRelevant, s) => currentlyRelevant || s.isInScope(VersionID(pair.asRef, "dummy")));
    if (isRelevantToASession) {
      val (from, until) = defaultDateBounds()

      runDifferenceForScope(SessionScope.forPairs(pair.domain.name, pair.key), from, until)
    }
  }

  /**
   * When pairs are deleted, we stop tracking their status in the pair scan map.
   */
  def onDeletePair(pair:DiffaPair) = {
    pairStates.synchronized { pairStates.remove(pair) }
  }

  def runScanForScope(scope:SessionScope, start:DateTime, end:DateTime) {
    pairsForScope(scope).foreach(runScanForPair(_))
  }

  def runScanForPair(pair:DiffaPair) {
    // Update the scan state ourselves. The policy itself will send an update shortly, but since that happens
    // asynchronously, we might have returned before then, and this may potentially result in clients seeing
    // a "Up To Date" view, even though we're just about to transition out of that state.
    updatePairScanState(pair, PairScanState.SCANNING)
    pairPolicyClient.scanPair(pair)
  }

  def runDifferenceForScope(scope:SessionScope, start:DateTime, end:DateTime)
  = pairsForScope(scope).foreach(pairPolicyClient.difference(_))

  def pairsForScope(scope:SessionScope):Seq[DiffaPair] = {
    scope.includedPairs.size match {
      case 0  => systemConfig.listPairs
      case _  => scope.includedPairs
    }
  }

  def forEachSession(id:VersionID, f: Function1[SessionCache,Any]) = {
    sessionsByKey.values.foreach(s => {
      if (s.isInScope(id)) {
        f(s)
      }
    })
  }

  def forEachSessionListener(s:SessionCache, f: (DifferencingListener) => Any) = {
    listeners.synchronized {
      listeners.get(s.sessionId) match {
        case None =>
        case Some(keyListeners) => {
          keyListeners.foreach(l => f(l))
        }
      }
    }
  }
  
  def reportPending(id:VersionID, lastUpdate:DateTime, upstreamVsn: String, downstreamVsn: String) =
    forEachSession(id, s => s.addPendingUnmatchedEvent(id, lastUpdate, upstreamVsn, downstreamVsn))

  def reportUnmatched(id:VersionID, lastUpdate:DateTime, upstreamVsn: String, downstreamVsn: String) = {
    forEachSession(id, s => {
      s.addReportableUnmatchedEvent(id, lastUpdate, upstreamVsn, downstreamVsn)

      // Streaming API
      forEachSessionListener(s, l => l.onMismatch(id, lastUpdate, upstreamVsn, downstreamVsn, LiveWindow))
    })
  }

  def addMatched(id:VersionID, vsn:String) = forEachSession(id, s => s.addMatchedEvent(id, vsn) )
  def upgradePending(id:VersionID) = {
    forEachSession(id, s => {
      val evt = s.upgradePendingUnmatchedEvent(id)
      if (evt != null) {
        log.debug("Processing upgrade from pending to unmatched for " + id)

        val timestamp = new DateTime()
        forEachSessionListener(s, l => l.onMismatch(id, timestamp, evt.upstreamVsn, evt.downstreamVsn, LiveWindow))
      } else {
        log.debug("Skipped upgrade from pending to unmatched for " + id + " as the event was not pending")
      }
    })
  }

  /**
   * Utility function to generate session ids based on hashing the query parameters
   */
  def generateSessionId(scope: SessionScope, start: DateTime, end: DateTime) = DigestUtils.md5Hex(scope.toString + start + end)

  /**
   * Utility function to make sure that a scope refers only to valid pairs.
   */
  def withValidScope[T](scope:SessionScope, f: () => T) = {
    // Ignore this because scope will be deprecated soon
    //scope.includedPairs.foreach(p => config.getPair(p))
    f()
  }

  def updatePairScanState(pair:DiffaPair, state:PairScanState) = {
    pairStates.synchronized {
      pairStates(pair) = state
    }
    log.info("Pair " + pair.identifier + " entered scan state: " + state)
  }
}
