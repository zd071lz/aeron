/*
 * Copyright 2014 - 2015 Real Logic Ltd.
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
package uk.co.real_logic.aeron.driver;

/**
 * Tracks a aeron client interest registration in a {@link NetworkPublication}.
 */
public class PublicationLink implements DriverManagedResourceProvider
{
    private final long registrationId;
    private final NetworkPublication publication;
    private final DirectLog directLog;
    private final AeronClient client;
    private final DriverManagedResource driverManagedResource = new PublicationLinkManagedResource();

    public PublicationLink(final long registrationId, final NetworkPublication publication, final AeronClient client)
    {
        this.registrationId = registrationId;
        this.publication = publication;
        this.directLog = null;
        this.client = client;
    }

    public PublicationLink(final long registrationId, final DirectLog directLog, final AeronClient client)
    {
        this.registrationId = registrationId;
        this.publication = null;
        this.client = client;
        this.directLog = directLog;
        directLog.incRef();
    }

    public void close()
    {
        if (null != publication)
        {
            publication.decRef();
        }

        if (null != directLog)
        {
            directLog.decRef();
        }
    }

    public long registrationId()
    {
        return registrationId;
    }

    public DriverManagedResource managedResource()
    {
        return driverManagedResource;
    }

    private class PublicationLinkManagedResource implements DriverManagedResource
    {
        private boolean reachedEndOfLife = false;

        public void onTimeEvent(long time, DriverConductor conductor)
        {
            if (client.hasTimedOut(time))
            {
                reachedEndOfLife = true;
            }
        }

        public boolean hasReachedEndOfLife()
        {
            return reachedEndOfLife;
        }

        public void timeOfLastStateChange(long time)
        {
            // not set this way
        }

        public long timeOfLastStateChange()
        {
            return client.timeOfLastKeepalive();
        }

        public void delete()
        {
            close();
        }
    }
}
