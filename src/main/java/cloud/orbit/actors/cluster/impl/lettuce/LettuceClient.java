/*
 Copyright (C) 2018 Electronic Arts Inc.  All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1.  Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
 2.  Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
 3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
     its contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
 EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
 DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package cloud.orbit.actors.cluster.impl.lettuce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.RedisCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LettuceClient<K, V>
{
    private static Logger logger = LoggerFactory.getLogger(LettuceClient.class);

    private final RedisClient redisClient;
    private final RedisCodec<K, V> codec;
    private final String redisUri;

    private RedisAsyncCommands<K, V> asyncCommands;

    public LettuceClient(final String resolvedUri, RedisCodec<K, V> codec)
    {
        this.redisUri = resolvedUri;
        this.redisClient = RedisClient.create(resolvedUri);
        this.codec = codec;
        this.asyncCommands = this.redisClient.connect(codec).async();
    }

    public String getRedisUri()
    {
        return this.redisUri;
    }

    public CompletableFuture<V> get(final K key) {
        return this.asyncCommands.get(key).toCompletableFuture();
    }

    public CompletableFuture<String> set(final K key, final V value) {
        return this.asyncCommands.set(key, value).toCompletableFuture();
    }

    public CompletableFuture<String> set(final K key, final V value, final long expireMs) {
        if (expireMs < 1) {
            return this.set(key, value);
        }
        return this.asyncCommands.set(key, value, SetArgs.Builder.px(expireMs)).toCompletableFuture();
    }

    public CompletableFuture<Long> del( final K key) {
        return this.asyncCommands.del(key).toCompletableFuture();
    }

    public CompletableFuture<List<String>> scan(final String matches) {
        // Batches of 1000?  TODO measure and adjust batch size if necessary
        return scan(matches, 1000);
    }

    public CompletableFuture<List<String>> scan(
            final String matches,
            final long count) {

        final List<String> existing = new ArrayList<>();

        return this.scan(existing, matches, count);

    }

    private CompletableFuture<List<String>> scan(
            final List<String> existing,
            final String matches,
            final long count) {

        return this.asyncCommands.scan(ScanArgs.Builder.limit(count).match(matches))
                .toCompletableFuture()
                .thenCompose(initialCursor -> this.scan(initialCursor, existing, matches, count));

    }

    private CompletableFuture<List<String>> scan(
            final KeyScanCursor<K> cursor,
            final List<String> existing,
            final String matches,
            final long count) {

        existing.addAll(cursor.getKeys().stream()
                .map(object -> Objects.toString(object, null))
                .collect(Collectors.toList()));

        if (cursor.isFinished()) {
            return CompletableFuture.completedFuture(existing);
        }

        return this.asyncCommands.scan(cursor, ScanArgs.Builder.limit(count).match(matches))
                .toCompletableFuture()
                .thenCompose(newCursor -> this.scan(newCursor, existing, matches, count));

    }

    public RedisAsyncCommands<K, V> getAsyncCommands() {
        if (this.asyncCommands != null && this.asyncCommands.isOpen()) {
            return this.asyncCommands;
        }
        this.asyncCommands = this.redisClient.connect(codec).async();
        return this.asyncCommands;
    }

    public void shutdown()
    {
        this.asyncCommands.shutdown(false);
        this.redisClient.shutdown();
    }
}
