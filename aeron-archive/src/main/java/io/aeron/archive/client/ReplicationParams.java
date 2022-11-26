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
package io.aeron.archive.client;

import io.aeron.Aeron;

/**
 * Contains the optional parameters that can be passed to a Replication Request. Controls the behaviour of the
 * replication including tagging, stop position, extending destination recordings, live merging, and setting the
 * maximum length of the file I/O operations.
 */
public class ReplicationParams
{
    private long stopPosition;
    private long dstRecordingId;
    private String liveDestination;
    private String replicationChannel;
    private long channelTagId;
    private long subscriptionTagId;
    private int fileIoMaxLength;

    /**
     * Initialise all parameters to defaults.
     */
    public ReplicationParams()
    {
        reset();
    }

    /**
     * Reset the state of the parameters to the default for reuse.
     *
     * @return this for a fluent API.
     */
    public ReplicationParams reset()
    {
        stopPosition = AeronArchive.NULL_POSITION;
        dstRecordingId = Aeron.NULL_VALUE;
        liveDestination = null;
        replicationChannel = null;
        channelTagId = Aeron.NULL_VALUE;
        subscriptionTagId = Aeron.NULL_VALUE;
        fileIoMaxLength = Aeron.NULL_VALUE;
        return this;
    }

    /**
     * Set the stop position for replication, default is {@link AeronArchive#NULL_POSITION}, which will continuously
     * replicate.
     *
     * @param stopPosition position to stop the replication at.
     * @return this for a fluent API
     */
    public ReplicationParams stopPosition(final long stopPosition)
    {
        this.stopPosition = stopPosition;
        return this;
    }

    /**
     * The stop position for this replication request.
     * @return stop position
     */
    public long stopPosition()
    {
        return stopPosition;
    }

    /**
     * The recording in the local archive to extend. Default is {@link Aeron#NULL_VALUE} which will trigger the creation
     * of a new recording in the destination archive.
     *
     * @param dstRecordingId destination recording to extend.
     * @return this for a fluent API.
     */
    public ReplicationParams dstRecordingId(final long dstRecordingId)
    {
        this.dstRecordingId = dstRecordingId;
        return this;
    }

    /**
     * Destination recording id to extend.
     *
     * @return destination recording id.
     */
    public long dstRecordingId()
    {
        return dstRecordingId;
    }

    /**
     * Destination for the live stream if merge is required. Default is null for no merge.
     *
     * @param liveChannel for the live stream merge
     * @return this for a fluent API.
     */
    public ReplicationParams liveDestination(final String liveChannel)
    {
        this.liveDestination = liveChannel;
        return this;
    }

    /**
     * Gets the destination for the live stream merge.
     *
     * @return destination for live stream merge.
     */
    public String liveDestination()
    {
        return liveDestination;
    }

    /**
     * Channel to use for replicating the recording, empty string will mean that the default channel is used.
     * @return channel to replicate the recording.
     */
    public String replicationChannel()
    {
        return replicationChannel;
    }

    /**
     * Channel use to replicate the recording. Default is null which will use the context's default replication
     * channel
     *
     * @param replicationChannel to use for replicating the recording.
     * @return this for a fluent API.
     */
    public ReplicationParams replicationChannel(final String replicationChannel)
    {
        this.replicationChannel = replicationChannel;
        return this;
    }

    /**
     * The channel used by the archive's subscription for replication will have the supplied channel tag applied to it.
     * The default value for channelTagId is {@link Aeron#NULL_VALUE}
     *
     * @param channelTagId tag to apply to the archive's subscription.
     * @return this for a fluent API
     */
    public ReplicationParams channelTagId(final long channelTagId)
    {
        this.channelTagId = channelTagId;
        return this;
    }

    /**
     * Gets channel tag id for the archive subscription.
     *
     * @return channel tag id.
     */
    public long channelTagId()
    {
        return channelTagId;
    }

    /**
     * The channel used by the archive's subscription for replication will have the supplied subscription tag applied to
     * it. The default value for subscriptionTagId is {@link Aeron#NULL_VALUE}
     *
     * @param subscriptionTagId tag to apply to the archive's subscription.
     * @return this for a fluent API
     */
    public ReplicationParams subscriptionTagId(final long subscriptionTagId)
    {
        this.subscriptionTagId = subscriptionTagId;
        return this;
    }

    /**
     * Gets subscription tag id for the archive subscription.
     *
     * @return subscription tag id.
     */
    public long subscriptionTagId()
    {
        return subscriptionTagId;
    }

    /**
     * The maximum size of a file operation when reading from the archive to execute the replication. Will use the value
     * defined in the context otherwise. This can be used reduce the size of file IO operations to lower the
     * priority of some replays. Setting it to a value larger than the context value will have no affect.
     *
     * @param fileIoMaxLength maximum length of a file I/O operation.
     * @return this for a fluent API
     */
    public ReplicationParams fileIoMaxLength(final int fileIoMaxLength)
    {
        this.fileIoMaxLength = fileIoMaxLength;
        return this;
    }

    /**
     * Gets the maximum length for file IO operations in the replay. Defaults to {@link Aeron#NULL_VALUE} if not
     * set, which will trigger the use of the Archive.Context default.
     *
     * @return maximum length of a file I/O operation.
     */
    public int fileIoMaxLength()
    {
        return this.fileIoMaxLength;
    }
}
