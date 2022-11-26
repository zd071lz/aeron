/*
 * Copyright 2014-2022 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.agent;

import io.aeron.Aeron;
import io.aeron.cluster.codecs.CloseReason;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.TimeUnit;

import static io.aeron.agent.ClusterEventCode.*;
import static io.aeron.agent.ClusterEventLogger.LOGGER;

class ClusterInterceptor
{
    static class ElectionStateChange
    {
        @Advice.OnMethodEnter
        static <E extends Enum<E>> void logStateChange(
            final int memberId,
            final E oldState,
            final E newState,
            final int leaderId,
            final long candidateTermId,
            final long leadershipTermId,
            final long logPosition,
            final long logLeadershipTermId,
            final long appendPosition,
            final long catchupPosition)
        {
            LOGGER.logElectionStateChange(
                memberId,
                oldState,
                newState,
                leaderId,
                candidateTermId,
                leadershipTermId,
                logPosition,
                logLeadershipTermId,
                appendPosition,
                catchupPosition);
        }
    }

    static class NewLeadershipTerm
    {
        @Advice.OnMethodEnter
        static void logOnNewLeadershipTerm(
            final int memberId,
            final long logLeadershipTermId,
            final long nextLeadershipTermId,
            final long nextTermBaseLogPosition,
            final long nextLogPosition,
            final long leadershipTermId,
            final long termBaseLogPosition,
            final long logPosition,
            final long leaderRecordingId,
            final long timestamp,
            final int leaderId,
            final int logSessionId,
            final int appVersion,
            final boolean isStartup)
        {
            LOGGER.logOnNewLeadershipTerm(
                memberId,
                logLeadershipTermId,
                nextLeadershipTermId,
                nextTermBaseLogPosition,
                nextLogPosition,
                leadershipTermId,
                termBaseLogPosition,
                logPosition,
                leaderRecordingId,
                timestamp,
                leaderId,
                logSessionId,
                appVersion,
                isStartup);
        }
    }

    static class ConsensusModuleStateChange
    {
        @Advice.OnMethodEnter
        static <E extends Enum<E>> void logStateChange(final int memberId, final E oldState, final E newState)
        {
            LOGGER.logStateChange(STATE_CHANGE, memberId, oldState, newState);
        }
    }

    static class ConsensusModuleRoleChange
    {
        @Advice.OnMethodEnter
        static <E extends Enum<E>> void logRoleChange(final int memberId, final E oldRole, final E newRole)
        {
            LOGGER.logStateChange(ROLE_CHANGE, memberId, oldRole, newRole);
        }
    }

    static class CanvassPosition
    {
        @Advice.OnMethodEnter
        static void logOnCanvassPosition(
            final int memberId,
            final long logLeadershipTermId,
            final long logPosition,
            final long leadershipTermId,
            final int followerMemberId,
            final int protocolVersion)
        {
            LOGGER.logOnCanvassPosition(
                memberId, logLeadershipTermId, logPosition, leadershipTermId, followerMemberId, protocolVersion);
        }
    }

    static class RequestVote
    {
        @Advice.OnMethodEnter
        static void logOnRequestVote(
            final int memberId,
            final long logLeadershipTermId,
            final long logPosition,
            final long candidateTermId,
            final int candidateId,
            final int protocolVersion)
        {
            LOGGER.logOnRequestVote(
                memberId, logLeadershipTermId, logPosition, candidateTermId, candidateId, protocolVersion);
        }
    }

    static class CatchupPosition
    {
        @Advice.OnMethodEnter
        static void logOnCatchupPosition(
            final int memberId,
            final long leadershipTermId,
            final long logPosition,
            final int followerMemberId,
            final String catchupEndpoint)
        {
            LOGGER.logOnCatchupPosition(memberId, leadershipTermId, logPosition, followerMemberId, catchupEndpoint);
        }
    }

    static class StopCatchup
    {
        @Advice.OnMethodEnter
        static void logOnStopCatchup(final int memberId, final long leadershipTermId, final int followerMemberId)
        {
            LOGGER.logOnStopCatchup(memberId, leadershipTermId, followerMemberId);
        }
    }

    static class TruncateLogEntry
    {
        @Advice.OnMethodEnter
        static <E extends Enum<E>> void onTruncateLogEntry(
            final int memberId,
            final E state,
            final long logLeadershipTermId,
            final long leadershipTermId,
            final long candidateTermId,
            final long commitPosition,
            final long logPosition,
            final long appendPosition,
            final long oldPosition,
            final long newPosition)
        {
            LOGGER.logOnTruncateLogEntry(
                memberId,
                state,
                logLeadershipTermId,
                leadershipTermId,
                candidateTermId,
                commitPosition,
                logPosition,
                appendPosition,
                oldPosition,
                newPosition);
        }
    }

    static class ReplayNewLeadershipTerm
    {
        @Advice.OnMethodEnter
        static void logOnReplayNewLeadershipTermEvent(
            final int memberId,
            final boolean isInElection,
            final long leadershipTermId,
            final long logPosition,
            final long timestamp,
            final long termBaseLogPosition,
            final TimeUnit timeUnit,
            final int appVersion)
        {
            LOGGER.logOnReplayNewLeadershipTermEvent(
                memberId,
                isInElection,
                leadershipTermId,
                logPosition,
                timestamp,
                termBaseLogPosition,
                timeUnit,
                appVersion);
        }
    }

    static class AppendPosition
    {
        @Advice.OnMethodEnter
        static void logOnAppendPosition(
            final int memberId,
            final long leadershipTermId,
            final long logPosition,
            final int followerMemberId,
            final short flags)
        {
            LOGGER.logOnAppendPosition(
                memberId,
                leadershipTermId,
                logPosition,
                followerMemberId,
                flags);
        }
    }

    static class CommitPosition
    {
        @Advice.OnMethodEnter
        static void logOnCommitPosition(
            final int memberId,
            final long leadershipTermId,
            final long logPosition,
            final int leaderMemberId)
        {
            LOGGER.logOnCommitPosition(memberId, leadershipTermId, logPosition, leaderMemberId);
        }
    }

    static class AddPassiveMember
    {
        @Advice.OnMethodEnter
        static void logOnAddPassiveMember(
            final int memberId,
            final long correlationId,
            final String passiveMember)
        {
            LOGGER.logOnAddPassiveMember(memberId, correlationId, passiveMember);
        }
    }

    static class AppendSessionClose
    {
        @Advice.OnMethodEnter
        static void logAppendSessionClose(
            final int memberId,
            final long sessionId,
            final CloseReason closeReason,
            final long leadershipTermId,
            final long timestamp,
            final TimeUnit timeUnit)
        {
            LOGGER.logAppendSessionClose(memberId, sessionId, closeReason, leadershipTermId, timestamp, timeUnit);
        }
    }

    static class DynamicJoinStateChange
    {
        @Advice.OnMethodEnter
        static <E extends Enum<E>> void logStateChange(final E oldState, final E newState, final int memberId)
        {
            LOGGER.logStateChange(DYNAMIC_JOIN_STATE_CHANGE, memberId, oldState, newState);
        }
    }

    static class ClusterBackupStateChange
    {
        @Advice.OnMethodEnter
        static <E extends Enum<E>> void logStateChange(final E oldState, final E newState, final long nowMs)
        {
            LOGGER.logStateChange(CLUSTER_BACKUP_STATE_CHANGE, Aeron.NULL_VALUE, oldState, newState);
        }
    }

    static class TerminationPosition
    {
        @Advice.OnMethodEnter
        static void logOnTerminationPosition(final int memberId, final long leadershipTermId, final long position)
        {
            LOGGER.logTerminationPosition(memberId, leadershipTermId, position);
        }
    }

    static class TerminationAck
    {
        @Advice.OnMethodEnter
        static void logOnTerminationAck(
            final int memberId, final long leadershipTermId, final long position, final int senderMemberId)
        {
            LOGGER.logTerminationAck(memberId, leadershipTermId, position, senderMemberId);
        }
    }

}
