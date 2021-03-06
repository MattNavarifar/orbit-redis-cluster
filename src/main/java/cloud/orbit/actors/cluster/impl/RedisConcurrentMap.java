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

package cloud.orbit.actors.cluster.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cloud.orbit.actors.cluster.impl.lettuce.LettuceClient;
import cloud.orbit.exception.NotImplementedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisScriptingCommands;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

/**
 * WARNING - Lettuce api for redis hash and set types come with some considerations.  Lettuce forces KKV generics onto
 * maps and sets where K - key of map or set, K - field of map or set, V - value associated with that field.  This may
 * give you trouble if you are expecting your name of the map or set and the fields in the map or set to be of different
 * types.  The way around this is to invoke lua directly and pass parameters into K[] or V... to purposely choose
 * which serializer to use.  Codecs of the same type for K and V i.e. <String, String> or <Object, Object> won't have
 * issues, but if you wanted mixed codecs i.e. <String, Object>, you will likely have issues.
 *
 */
public class RedisConcurrentMap<K, V> implements ConcurrentMap<K, V>
{
    private static Logger logger = LoggerFactory.getLogger(RedisConcurrentMap.class);

    private final String name;
    private final LettuceClient<String, Object> redisClient;

    public RedisConcurrentMap(final String name, final LettuceClient<String, Object> redisClient) {
        this.name = name;
        this.redisClient = redisClient;
    }

    @Override
    public int size()
    {
        return redisClient.commands().hlen(name).toCompletableFuture().join().intValue();
    }

    @Override
    public boolean isEmpty()
    {
        return size() == 0;
    }

    private static final String scriptContains =
            "return redis.call('hexists', KEYS[1], ARGV[1]);\n";
    @Override
    public boolean containsKey(final Object key)
    {
        return (Boolean)eval(scriptContains, ScriptOutputType.BOOLEAN, new String[]{name}, key).join();
    }

    @Override
    public boolean containsValue(final Object value)
    {
        throw new NotImplementedException();
    }

    private static final String scriptGet =
            "return redis.call('hget', KEYS[1], ARGV[1]);\n";
    @Override
    public V get(final Object key)
    {
        return (V)eval(scriptGet, ScriptOutputType.VALUE, new String[]{name}, key).join();
    }

    private static final String scriptPut =
            "local v = redis.call('hget', KEYS[1], ARGV[1]);\n" +
            "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]);\n" +
            "return v\n";
    @Override
    public V put(final K key, final V value)
    {
        return (V)eval(scriptPut, ScriptOutputType.VALUE, new String[]{name}, key, value).join();
    }

    private static final String scriptRemove =
            "local v = redis.call('hget', KEYS[1], ARGV[1]);\n" +
            "redis.call('hdel', KEYS[1], ARGV[1]);\n" +
            "return v";
    @Override
    public V remove(final Object key)
    {
        return (V)eval(scriptRemove, ScriptOutputType.VALUE, new String[]{name}, key).join();
    }

    private static final String scriptRemoveMatchingOldValue =
            "if redis.call('hget', KEYS[1], ARGV[1]) == ARGV[2] then\n" +
            "  return redis.call('hdel', KEYS[1], ARGV[1])\n" +
            "else\n" +
            "  return 0\n" +
            "end\n";
    @Override
    public boolean remove(final Object key, final Object oldValue)
    {
        return (Boolean)eval(scriptRemoveMatchingOldValue, ScriptOutputType.BOOLEAN, new String[]{name}, key, oldValue).join();
    }

    private CompletableFuture<?> eval(final String script, final ScriptOutputType type, String[] keys, Object... args) {
        return this.redisClient.commands().eval(script, type, keys, args)
                .toCompletableFuture();
    }

    @Override
    public void putAll(final Map<? extends K, ? extends V> m) {
        throw new NotImplementedException();
    }

    @Override
    public void clear()
    {
        redisClient.commands().del(name).toCompletableFuture().join();
    }

    @Override
    public Set<K> keySet()
    {
        throw new NotImplementedException();
    }

    @Override
    public Collection<V> values()
    {
        throw new NotImplementedException();
    }

    @Override
    public Set<Entry<K, V>> entrySet()
    {
        throw new NotImplementedException();
    }


    private static final String scriptPutIfAbsent =
            "if redis.call('hsetnx', KEYS[1], ARGV[1], ARGV[2]) == 1 then\n" +
            "  return nil\n" +
            "else \n" +
            "  return redis.call('hget', KEYS[1], ARGV[1])\n" +
            "end";

    @Override
    public V putIfAbsent(final K key, final V value)
    {
        return (V)eval(scriptPutIfAbsent, ScriptOutputType.VALUE, new String[]{name}, key, value).join();
    }

    private static final String scriptReplaceMatchingOldValue =
            "if redis.call('hget', KEYS[1], ARGV[1]) == ARGV[2] then\n" +
            "  redis.call('hset', KEYS[1], ARGV[1], ARGV[3]);\n" +
            "  return 1;\n" +
            "else\n" +
            "  return 0;\n" +
            "end\n";
    @Override
    public boolean replace(final Object key, final Object oldValue, final Object newValue)
    {
        return (Boolean)eval(scriptReplaceMatchingOldValue, ScriptOutputType.BOOLEAN, new String[]{name}, key, oldValue, newValue).join();
    }

    private static final String scriptReplace =
            "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then\n" +
            "  local v = redis.call('hget', KEYS[1], ARGV[1]); \n" +
            "  redis.call('hset', KEYS[1], ARGV[1], ARGV[2]);\n" +
            "  return v;\n" +
            "else\n" +
            "  return nil;\n" +
            "end\n";

    @Override
    public V replace(final K key, final V value)
    {
        return (V)eval(scriptReplace, ScriptOutputType.VALUE, new String[]{name}, key, value).join();
    }
}
