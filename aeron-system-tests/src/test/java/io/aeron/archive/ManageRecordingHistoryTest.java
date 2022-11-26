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
package io.aeron.archive;

import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.Publication;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.RecordingSignal;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.TestContexts;
import io.aeron.test.Tests;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static io.aeron.archive.ArchiveSystemTests.*;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(InterruptingTestCallback.class)
class ManageRecordingHistoryTest
{
    private static final int TERM_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int SEGMENT_LENGTH = TERM_LENGTH * 2;
    private static final int STREAM_ID = 1033;
    private static final int MTU_LENGTH = 1024;

    private final ChannelUriStringBuilder uriBuilder = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .mtu(MTU_LENGTH)
        .termLength(TERM_LENGTH);
    private TestMediaDriver driver;
    private Archive archive;
    private Aeron aeron;
    private AeronArchive aeronArchive;
    private TestRecordingSignalConsumer signalConsumer;

    @RegisterExtension
    final SystemTestWatcher systemTestWatcher = new SystemTestWatcher();

    @BeforeEach
    void before(@TempDir final Path tempDir)
    {
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .publicationTermBufferLength(TERM_LENGTH)
            .termBufferSparseFile(true)
            .threadingMode(ThreadingMode.SHARED)
            .spiesSimulateConnection(true)
            .dirDeleteOnStart(true);

        final Archive.Context archiveCtx = TestContexts.localhostArchive()
            .catalogCapacity(CATALOG_CAPACITY)
            .segmentFileLength(SEGMENT_LENGTH)
            .deleteArchiveOnStart(true)
            .archiveDir(tempDir.resolve("archive").toFile())
            .fileSyncLevel(0)
            .threadingMode(ArchiveThreadingMode.SHARED);

        try
        {
            driver = TestMediaDriver.launch(driverCtx, systemTestWatcher);
            archive = Archive.launch(archiveCtx);
        }
        finally
        {
            systemTestWatcher.dataCollector().add(driverCtx.aeronDirectory());
            systemTestWatcher.dataCollector().add(archiveCtx.archiveDir());
        }

        aeron = Aeron.connect();

        aeronArchive = AeronArchive.connect(
            TestContexts.localhostAeronArchive()
                .aeron(aeron));

        signalConsumer = injectRecordingSignalConsumer(aeronArchive);
    }

    @AfterEach
    void after()
    {
        CloseHelper.closeAll(aeronArchive, aeron, archive, driver);
    }

    @Test
    @InterruptAfter(10)
    void shouldPurgeForStreamJoinedAtTheBeginning()
    {
        final String messagePrefix = "Message-Prefix-";
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;

        try (Publication publication = aeronArchive.addRecordedPublication(uriBuilder.build(), STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            Tests.awaitPosition(counters, counterId, publication.position());

            final long startPosition = 0L;
            final long segmentFileBasePosition = AeronArchive.segmentFileBasePosition(
                startPosition, SEGMENT_LENGTH * 2L, TERM_LENGTH, SEGMENT_LENGTH);

            signalConsumer.reset();
            final long count = aeronArchive.purgeSegments(recordingId, segmentFileBasePosition);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.DELETE);
            assertEquals(recordingId, signalConsumer.recordingId);
            assertEquals(2L, count);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.STOP);
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldPurgeForLateJoinedStream() throws IOException
    {
        final String messagePrefix = "Message-Prefix-";
        final int initialTermId = 7;
        final long targetPosition = (SEGMENT_LENGTH * 13L) + 1;
        final long startPosition = (SEGMENT_LENGTH * 10L) + 7 * FRAME_ALIGNMENT;
        uriBuilder.initialPosition(startPosition, initialTermId, TERM_LENGTH);

        try (Publication publication = aeronArchive.addRecordedExclusivePublication(uriBuilder.build(), STREAM_ID))
        {
            assertEquals(startPosition, publication.position());

            final CountersReader counters = aeron.countersReader();
            final int counterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            Tests.awaitPosition(counters, counterId, publication.position());

            final File archiveDir = archive.context().archiveDir();
            long position = 0;
            boolean deleted = false;
            while (position < startPosition - SEGMENT_LENGTH)
            {
                final String segmentFileName = Archive.segmentFileName(recordingId, position);
                if (deleted)
                {
                    assertTrue(new File(archiveDir, segmentFileName + ".del").createNewFile());
                }
                else
                {
                    assertTrue(new File(archiveDir, segmentFileName).createNewFile());
                }
                deleted = !deleted;
                position += SEGMENT_LENGTH;
            }

            final String fileNamePrefix = recordingId + "-";
            final String[] recordingFiles = archiveDir.list((dir, name) -> name.startsWith(fileNamePrefix));
            assertThat(recordingFiles, arrayWithSize(14));

            final long segmentFileBasePosition = AeronArchive.segmentFileBasePosition(
                startPosition,
                startPosition + SEGMENT_LENGTH + TERM_LENGTH + FRAME_ALIGNMENT * 5,
                TERM_LENGTH,
                SEGMENT_LENGTH);

            signalConsumer.reset();
            final long purgeSegments = aeronArchive.purgeSegments(recordingId, segmentFileBasePosition);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.DELETE);
            assertEquals(recordingId, signalConsumer.recordingId);
            assertEquals(11L, purgeSegments);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.STOP);

            final String[] files = archiveDir.list((dir, name) -> name.startsWith(fileNamePrefix));
            assertThat(files, arrayContainingInAnyOrder(
                Archive.segmentFileName(recordingId, SEGMENT_LENGTH * 13L),
                Archive.segmentFileName(recordingId, SEGMENT_LENGTH * 12L),
                Archive.segmentFileName(recordingId, SEGMENT_LENGTH * 11L)));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldDetachThenAttachFullSegments()
    {
        final String messagePrefix = "Message-Prefix-";
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;

        try (Publication publication = aeronArchive.addRecordedPublication(uriBuilder.build(), STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            Tests.awaitPosition(counters, counterId, publication.position());

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.STOP);
            assertEquals(recordingId, signalConsumer.recordingId);

            final long startPosition = 0L;
            final long segmentFileBasePosition = AeronArchive.segmentFileBasePosition(
                startPosition, SEGMENT_LENGTH * 2L, TERM_LENGTH, SEGMENT_LENGTH);

            aeronArchive.detachSegments(recordingId, segmentFileBasePosition);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            final long attachSegments = aeronArchive.attachSegments(recordingId);
            assertEquals(2L, attachSegments);
            assertEquals(startPosition, aeronArchive.getStartPosition(recordingId));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldDetachThenAttachWhenStartNotSegmentAligned()
    {
        final String messagePrefix = "Message-Prefix-";
        final int initialTermId = 7;
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;
        final long startPosition = (TERM_LENGTH * 2L) + (FRAME_ALIGNMENT * 2L);
        uriBuilder.initialPosition(startPosition, initialTermId, TERM_LENGTH);

        try (Publication publication = aeronArchive.addRecordedExclusivePublication(uriBuilder.build(), STREAM_ID))
        {
            assertEquals(startPosition, publication.position());

            final CountersReader counters = aeron.countersReader();
            final int counterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            Tests.awaitPosition(counters, counterId, publication.position());

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.STOP);
            assertEquals(recordingId, signalConsumer.recordingId);

            final long segmentFileBasePosition = AeronArchive.segmentFileBasePosition(
                startPosition, startPosition + (SEGMENT_LENGTH * 2L), TERM_LENGTH, SEGMENT_LENGTH);

            aeronArchive.detachSegments(recordingId, segmentFileBasePosition);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            final long attachSegments = aeronArchive.attachSegments(recordingId);
            assertEquals(2L, attachSegments);
            assertEquals(startPosition, aeronArchive.getStartPosition(recordingId));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldDeleteDetachedFullSegments()
    {
        final String messagePrefix = "Message-Prefix-";
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;

        try (Publication publication = aeronArchive.addRecordedPublication(uriBuilder.build(), STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            Tests.awaitPosition(counters, counterId, publication.position());

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.STOP);
            assertEquals(recordingId, signalConsumer.recordingId);

            final String prefix = recordingId + "-";
            final File archiveDir = archive.context().archiveDir();
            final String[] files = archiveDir.list((dir, name) -> name.startsWith(prefix));
            assertThat(files, arrayWithSize(4));

            final long startPosition = 0L;
            final long segmentFileBasePosition = AeronArchive.segmentFileBasePosition(
                startPosition, SEGMENT_LENGTH * 2L, TERM_LENGTH, SEGMENT_LENGTH);

            aeronArchive.detachSegments(recordingId, segmentFileBasePosition);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            signalConsumer.reset();
            final long deletedSegments = aeronArchive.deleteDetachedSegments(recordingId);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.DELETE);
            assertEquals(2L, deletedSegments);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            final String[] updatedFiles = archiveDir.list((dir, name) -> name.startsWith(prefix));
            assertThat(updatedFiles, arrayContainingInAnyOrder(
                Archive.segmentFileName(recordingId, segmentFileBasePosition),
                Archive.segmentFileName(recordingId, segmentFileBasePosition + SEGMENT_LENGTH)
            ));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldDeleteDetachedSegmentsWhenStartNotSegmentAligned()
    {
        final String messagePrefix = "Message-Prefix-";
        final int initialTermId = 7;
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;
        final long startPosition = (TERM_LENGTH * 2L) + (FRAME_ALIGNMENT * 2L);
        uriBuilder.initialPosition(startPosition, initialTermId, TERM_LENGTH);

        try (Publication publication = aeronArchive.addRecordedExclusivePublication(uriBuilder.build(), STREAM_ID))
        {
            assertEquals(startPosition, publication.position());

            final CountersReader counters = aeron.countersReader();
            final int counterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            Tests.awaitPosition(counters, counterId, publication.position());

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, recordingId, RecordingSignal.STOP);

            final String prefix = recordingId + "-";
            final String[] files = archive.context().archiveDir().list((dir, name) -> name.startsWith(prefix));
            assertThat(files, arrayWithSize(3));

            final long segmentFileBasePosition = AeronArchive.segmentFileBasePosition(
                startPosition, startPosition + (SEGMENT_LENGTH * 2L), TERM_LENGTH, SEGMENT_LENGTH);

            aeronArchive.detachSegments(recordingId, segmentFileBasePosition);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            signalConsumer.reset();
            final long deletedSegments = aeronArchive.deleteDetachedSegments(recordingId);
            awaitSignal(aeronArchive, signalConsumer, recordingId, RecordingSignal.DELETE);
            assertEquals(2L, deletedSegments);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(recordingId));

            final String[] updatedFiles = archive.context().archiveDir()
                .list((dir, name) -> name.startsWith(prefix));
            assertThat(
                updatedFiles,
                arrayContaining(Archive.segmentFileName(recordingId, segmentFileBasePosition)));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldMigrateSegmentsForStreamJoinedAtTheBeginning() throws IOException
    {
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;
        try (Publication publication = aeronArchive.addRecordedPublication(uriBuilder.build(), STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int dstCounterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long dstRecordingId = RecordingPos.getRecordingId(counters, dstCounterId);

            offerToPosition(publication, "dst-message-", targetPosition);
            Tests.awaitPosition(counters, dstCounterId, publication.position());

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, dstRecordingId, RecordingSignal.STOP);

            final long startPosition = 0L;
            final long migratePosition = AeronArchive.segmentFileBasePosition(
                startPosition, SEGMENT_LENGTH * 2L, TERM_LENGTH, SEGMENT_LENGTH);

            signalConsumer.reset();
            final long count = aeronArchive.purgeSegments(dstRecordingId, migratePosition);
            awaitSignal(aeronArchive, signalConsumer, dstRecordingId, RecordingSignal.DELETE);
            assertEquals(2L, count);
            assertEquals(migratePosition, aeronArchive.getStartPosition(dstRecordingId));

            final long srcRecordingId;
            final String migrateChannel = uriBuilder
                .initialPosition(startPosition, publication.initialTermId(), TERM_LENGTH)
                .endpoint("localhost:4444")
                .build();

            try (Publication migratePub = aeronArchive.addRecordedExclusivePublication(migrateChannel, STREAM_ID))
            {
                final int srcCounterId = Tests.awaitRecordingCounterId(counters, migratePub.sessionId());
                srcRecordingId = RecordingPos.getRecordingId(counters, srcCounterId);

                offerToPosition(migratePub, "src-message-", SEGMENT_LENGTH * 4 + TERM_LENGTH + FRAME_ALIGNMENT);
                Tests.awaitPosition(counters, srcCounterId, migratePub.position());

                signalConsumer.reset();
                aeronArchive.stopRecording(migratePub);
                awaitSignal(aeronArchive, signalConsumer, srcRecordingId, RecordingSignal.STOP);
            }

            signalConsumer.reset();
            aeronArchive.truncateRecording(srcRecordingId, migratePosition);
            awaitSignal(aeronArchive, signalConsumer, srcRecordingId, RecordingSignal.DELETE);

            final File archiveDir = archive.context().archiveDir();
            final String srcPrefix = srcRecordingId + "-";
            String[] srcFiles = archiveDir.list((dir, name) -> name.startsWith(srcPrefix));
            assertThat(srcFiles, arrayContainingInAnyOrder(
                Archive.segmentFileName(srcRecordingId, 0),
                Archive.segmentFileName(srcRecordingId, SEGMENT_LENGTH)
            ));

            final Path srcFile =
                new File(archiveDir, Archive.segmentFileName(srcRecordingId, migratePosition) + ".del").toPath();
            Files.write(srcFile, new byte[]{ 0x1, 0x2, 0x3 }, StandardOpenOption.CREATE_NEW);

            final String dstPrefix = dstRecordingId + "-";
            String[] dstFiles = archiveDir.list((dir, name) -> name.startsWith(dstPrefix));
            assertThat(dstFiles, arrayContainingInAnyOrder(
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH * 2L),
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH * 3L)
            ));
            final Path dstFile =
                new File(archiveDir, Archive.segmentFileName(dstRecordingId, migratePosition)).toPath();
            final byte[] dstBytes = Files.readAllBytes(dstFile);
            assertEquals(SEGMENT_LENGTH, dstBytes.length);

            signalConsumer.reset();
            final long migratedSegments = aeronArchive.migrateSegments(srcRecordingId, dstRecordingId);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.DELETE);
            assertEquals(srcRecordingId, signalConsumer.recordingId);
            assertEquals(2L, migratedSegments);
            assertEquals(startPosition, aeronArchive.getStartPosition(dstRecordingId));

            srcFiles = archiveDir.list((dir, name) -> name.startsWith(srcPrefix));
            assertThat(srcFiles, arrayWithSize(0));

            dstFiles = archiveDir.list((dir, name) -> name.startsWith(dstPrefix));
            assertThat(dstFiles, arrayContainingInAnyOrder(
                Archive.segmentFileName(dstRecordingId, 0),
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH),
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH * 2L),
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH * 3L)
            ));

            final byte[] migratedBytes = Files.readAllBytes(dstFile);
            assertArrayEquals(dstBytes, migratedBytes);
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldMigrateSegmentsForStreamNotSegmentAligned() throws IOException
    {
        final int initialTermId = 7;
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;
        final long startPosition = (TERM_LENGTH * 2L) + (FRAME_ALIGNMENT * 2L);
        uriBuilder.initialPosition(startPosition, initialTermId, TERM_LENGTH);

        try (Publication publication = aeronArchive.addRecordedExclusivePublication(uriBuilder.build(), STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int dstCounterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long dstRecordingId = RecordingPos.getRecordingId(counters, dstCounterId);

            offerToPosition(publication, "dst-message-", targetPosition);
            Tests.awaitPosition(counters, dstCounterId, publication.position());

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, dstRecordingId, RecordingSignal.STOP);

            final long segmentFileBasePosition = AeronArchive.segmentFileBasePosition(
                startPosition, startPosition + (SEGMENT_LENGTH * 2L), TERM_LENGTH, SEGMENT_LENGTH);

            signalConsumer.reset();
            final long purgedSegments = aeronArchive.purgeSegments(dstRecordingId, segmentFileBasePosition);
            awaitSignal(aeronArchive, signalConsumer, dstRecordingId, RecordingSignal.DELETE);
            assertEquals(2L, purgedSegments);
            assertEquals(segmentFileBasePosition, aeronArchive.getStartPosition(dstRecordingId));

            final long srcRecordingId;
            final String migrateChannel = uriBuilder
                .initialPosition(startPosition, initialTermId, TERM_LENGTH)
                .endpoint("localhost:4444")
                .build();

            try (Publication migratePub = aeronArchive.addRecordedExclusivePublication(migrateChannel, STREAM_ID))
            {
                final int srcCounterId = Tests.awaitRecordingCounterId(counters, migratePub.sessionId());
                srcRecordingId = RecordingPos.getRecordingId(counters, srcCounterId);

                offerToPosition(migratePub, "src-message-", segmentFileBasePosition);
                Tests.awaitPosition(counters, srcCounterId, migratePub.position());

                signalConsumer.reset();
                aeronArchive.stopRecording(migratePub);
                awaitSignal(aeronArchive, signalConsumer, srcRecordingId, RecordingSignal.STOP);
            }

            final File archiveDir = archive.context().archiveDir();
            final String srcPrefix = srcRecordingId + "-";
            String[] srcFiles = archiveDir.list((dir, name) -> name.startsWith(srcPrefix));
            assertThat(srcFiles, arrayContainingInAnyOrder(
                Archive.segmentFileName(srcRecordingId, SEGMENT_LENGTH),
                Archive.segmentFileName(srcRecordingId, SEGMENT_LENGTH * 2L),
                Archive.segmentFileName(srcRecordingId, SEGMENT_LENGTH * 3L)
            ));

            final String dstPrefix = dstRecordingId + "-";
            String[] dstFiles = archiveDir.list((dir, name) -> name.startsWith(dstPrefix));
            assertThat(dstFiles, arrayContaining(Archive.segmentFileName(dstRecordingId, segmentFileBasePosition)));

            final byte[] srcBytes = Files.readAllBytes(
                new File(archiveDir, Archive.segmentFileName(srcRecordingId, segmentFileBasePosition)).toPath());
            assertEquals(SEGMENT_LENGTH, srcBytes.length);
            final byte[] dstBytes = Files.readAllBytes(
                new File(archiveDir, Archive.segmentFileName(dstRecordingId, segmentFileBasePosition)).toPath());
            assertEquals(SEGMENT_LENGTH, dstBytes.length);
            assertThat(srcBytes, not(equalTo(dstBytes)));

            signalConsumer.reset();
            final long migratedSegments = aeronArchive.migrateSegments(srcRecordingId, dstRecordingId);
            awaitSignal(aeronArchive, signalConsumer, srcRecordingId, RecordingSignal.DELETE);
            assertEquals(2L, migratedSegments);
            assertEquals(startPosition, aeronArchive.getStartPosition(dstRecordingId));
            assertEquals(startPosition, aeronArchive.getStopPosition(srcRecordingId));

            srcFiles = archiveDir.list((dir, name) -> name.startsWith(srcPrefix));
            assertThat(srcFiles, arrayWithSize(0));

            dstFiles = archiveDir.list((dir, name) -> name.startsWith(dstPrefix));
            assertThat(dstFiles, arrayContainingInAnyOrder(
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH),
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH * 2L),
                Archive.segmentFileName(dstRecordingId, SEGMENT_LENGTH * 3L)
            ));

            final byte[] migratedBytes = Files.readAllBytes(
                new File(archiveDir, Archive.segmentFileName(dstRecordingId, segmentFileBasePosition)).toPath());
            assertArrayEquals(dstBytes, migratedBytes);
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldPurgeRecording() throws IOException
    {
        final String messagePrefix = "Message-Prefix-";
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;

        try (Publication publication = aeronArchive.addRecordedPublication(uriBuilder.build(), STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Tests.awaitRecordingCounterId(counters, publication.sessionId());
            final long newRecordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            Tests.awaitPosition(counters, counterId, publication.position());

            signalConsumer.reset();
            aeronArchive.stopRecording(publication);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.STOP);
            assertEquals(newRecordingId, signalConsumer.recordingId);

            final String prefix = newRecordingId + "-";
            final File archiveDir = archive.context().archiveDir();
            assertTrue(new File(archiveDir, prefix + (SEGMENT_LENGTH * 4L) + ".rec").createNewFile());
            assertTrue(new File(archiveDir, prefix + (SEGMENT_LENGTH * 5L) + ".rec.del").createNewFile());

            signalConsumer.reset();
            aeronArchive.purgeRecording(newRecordingId);
            awaitSignal(aeronArchive, signalConsumer, RecordingSignal.DELETE);

            assertThat(archiveDir.list(((dir, name) -> name.startsWith(prefix))), arrayWithSize(0));
        }
    }
}
