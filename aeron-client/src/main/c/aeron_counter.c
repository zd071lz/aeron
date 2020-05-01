/*
 * Copyright 2014-2020 Real Logic Limited.
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

#include "aeron_counter.h"
#include "util/aeron_error.h"

int aeron_counter_create(
    aeron_counter_t **counter,
    aeron_client_conductor_t *conductor,
    int64_t registration_id,
    int32_t counter_id,
    int64_t *counter_addr)
{
    aeron_counter_t *_counter;

    *counter = NULL;
    if (aeron_alloc((void **)&_counter, sizeof(aeron_counter_t)) < 0)
    {
        int errcode = errno;

        aeron_set_err(errcode, "aeron_counter_create (%d): %s", errcode, strerror(errcode));
        return -1;
    }

    _counter->command_base.type = AERON_CLIENT_TYPE_COUNTER;

    _counter->counter_addr = counter_addr;

    _counter->conductor = conductor;
    _counter->registration_id = registration_id;
    _counter->counter_id = counter_id;
    _counter->is_closed = false;

    *counter = _counter;
    return 0;
}

int aeron_counter_delete(aeron_counter_t *counter)
{
    aeron_free(counter);
    return 0;
}

int aeron_counter_close(aeron_counter_t *counter)
{
    return NULL != counter ?
        aeron_client_conductor_async_close_counter(counter->conductor, counter) : 0;
}