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

import org.junit.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import cloud.orbit.actors.cluster.IntegrationTest;
import cloud.orbit.actors.cluster.NodeAddress;
import cloud.orbit.actors.cluster.NodeAddressImpl;
import cloud.orbit.actors.cluster.RedisClusterConfig;
import cloud.orbit.actors.cluster.impl.lettuce.FstObjectCodec;
import cloud.orbit.actors.cluster.impl.lettuce.FstStringObjectCodec;
import cloud.orbit.actors.cluster.impl.lettuce.LettuceClient;
import cloud.orbit.actors.cluster.impl.lettuce.LettucePubSubClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class LettuceMapTest
{

    RedisClusterConfig config;
    RedisConnectionManager connectionManager;

    FstObjectCodec codec = new FstObjectCodec();

    boolean setup = false;
    String clusterName = "testcluster";

    @Before
    public void setup()
    {
        if (setup)
        {
            return;
        }

        config = new RedisClusterConfig();
        connectionManager = new RedisConnectionManager(config);
        setup = true;
    }

    @Test
    @Category(IntegrationTest.class)
    public void testClientsCreated()
    {
        Assert.assertNotNull(config);

        List<LettuceClient<String, Object>> clients = connectionManager.getActorDirectoryClients();
        Assert.assertFalse(clients.isEmpty());

        clients = connectionManager.getNodeDirectoryClients();
        Assert.assertFalse(clients.isEmpty());

        List<LettucePubSubClient> mclients = connectionManager.getActiveMessagingClients();
        Assert.assertFalse(mclients.isEmpty());
    }

    @Test
    @Category(IntegrationTest.class)
    public void testScript()
    {

        RedisCodec c = StringCodec.UTF8;
        RedisCodec codec = new FstStringObjectCodec();
        LettuceClient<String, Object> client = new LettuceClient<>("redis://localhost:6379", c, 5000, false, false);
        String script = "return 1 + 1";

        String shaDigest = client.commands().digest(script);
        String shaCached = client.commands().scriptLoad(script).toCompletableFuture().join();

        Assert.assertEquals(shaCached, shaDigest);

        client = new LettuceClient<>("redis://localhost:6379", codec, 5000, false, false);
        client.commands().eval(script, ScriptOutputType.INTEGER).toCompletableFuture().join();
        Long r = (Long) client.commands().evalsha(shaCached, ScriptOutputType.INTEGER).toCompletableFuture().join();

        Assert.assertEquals(2, r.longValue());
    }

    @Test
    @Category(IntegrationTest.class)
    public void testScriptPut()
    {

        RedisCodec c = StringCodec.UTF8;
        RedisCodec codec = new FstStringObjectCodec();
        LettuceClient<String, Object> client = new LettuceClient<>("redis://localhost:6379", c, 5000, false, false);

        final String script =
                "local v = redis.call('hget', KEYS[1], ARGV[1]);\n" +
                        "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]);\n" +
                        "return v\n";

        String shaDigest = client.commands().digest(script);
        String shaCached = client.commands().scriptLoad(script).toCompletableFuture().join();

        Assert.assertEquals(shaCached, shaDigest);

        client = new LettuceClient<>("redis://localhost:6379", codec, 5000, false, false);
        client.commands().eval(script, ScriptOutputType.VALUE, new String[]{ "key" }, "field", "value").toCompletableFuture().join();
        String r = (String) client.commands().evalsha(shaCached, ScriptOutputType.VALUE, new String[]{ "key" }, "field", "value").toCompletableFuture().join();
    }

    @Test
    @Category(IntegrationTest.class)
    public void testMap()
    {
        List<LettuceClient<String, Object>> clients = connectionManager.getActorDirectoryClients();
        Assert.assertFalse(clients.isEmpty());
        RedisShardedMap map = new RedisShardedMap("test.map", connectionManager.getActorDirectoryClients(), 10);
        map.clear();
        Assert.assertTrue(map.isEmpty());
        String k = "key";
        String v = "value";
        map.put(k, v);
        String value = (String) map.get(k);
        Assert.assertEquals(v, value);
        Assert.assertFalse(map.isEmpty());
        Assert.assertTrue(map.containsKey(k));
        map.remove("key");
        Assert.assertTrue(map.isEmpty());
    }

    @Test
    @Category(IntegrationTest.class)
    public void testMapSize()
    {
        RedisShardedMap map = new RedisShardedMap("test.map", connectionManager.getActorDirectoryClients(), 10);
        map.clear();
        Assert.assertTrue(map.isEmpty());

        Map m = new HashMap<>();
        m.put("a", "1");
        m.put("b", "2");
        m.put("c", "3");
        map.putAll(m);

        int s = map.size();
        Assert.assertEquals(m.size(), s);

    }

    @Test
    @Category(IntegrationTest.class)
    public void testMapReplace()
    {
        RedisShardedMap map = new RedisShardedMap("test.map", connectionManager.getActorDirectoryClients(), 10);
        map.clear();
        Assert.assertTrue(map.isEmpty());

        map.put("a", "1");
        Assert.assertEquals("1", map.get("a"));
        map.replace("a", "wrong", "new");
        Assert.assertEquals("1", map.get("a"));
        map.replace("a", "1", "new");
        Assert.assertEquals("new", map.get("a"));

        map.replace("a", "new2");
        Assert.assertEquals("new2", map.get("a"));
    }

    @Test
    @Category(IntegrationTest.class)
    public void testMapRemove()
    {
        RedisShardedMap map = new RedisShardedMap("test.map", connectionManager.getActorDirectoryClients(), 10);
        map.clear();
        Assert.assertTrue(map.isEmpty());

        Assert.assertNull(map.put("a", "1"));
        Assert.assertEquals("1", map.get("a"));

        map.remove("a");
        Assert.assertTrue(map.isEmpty());

        map.put("a", "1");
        map.remove("a", "wrong");
        Assert.assertEquals("1", map.get("a"));

        map.remove("a", "1");
        Assert.assertTrue(map.isEmpty());
    }

    @Test
    @Category(IntegrationTest.class)
    public void testRemove()
    {
        RedisShardedMap map = new RedisShardedMap("test.map", connectionManager.getActorDirectoryClients(), 10);
        map.clear();
        map.put("a", "1");
        Assert.assertFalse(map.isEmpty());
        map.remove("a");
        Assert.assertTrue(map.isEmpty());
        Assert.assertNull(map.put("a", "1"));
        Assert.assertFalse(map.isEmpty());
        Assert.assertFalse(map.remove("a", "wrong"));
        Assert.assertFalse(map.isEmpty());
        Assert.assertNotNull(map.remove("a", "1"));
        Assert.assertTrue(map.isEmpty());
    }

    @Test
    @Category(IntegrationTest.class)
    public void testScan()
    {

        String nodeKey = UUID.randomUUID().toString();
        List<LettuceClient<String, Object>> clients = connectionManager.getNodeDirectoryClients();

        LettuceClient<String, Object> client = clients.get(0);

        String matches = nodeKey + "*";
        List<String> results = client.scan(matches).join();
        Assert.assertTrue(results.isEmpty());

        client.set(nodeKey + ".1", "1").join();
        client.set(nodeKey + ".2", "2").join();

        results = client.scan(matches).join();
        Assert.assertTrue(results.size() == 2);
    }

    @Test
    @Category(IntegrationTest.class)
    public void nodeScanTest()
    {

        NodeAddress localAddress = new NodeAddressImpl(UUID.randomUUID());
        final String nodeKey = RedisKeyGenerator.nodeKey(clusterName, localAddress.toString());
        long expire = TimeUnit.SECONDS.toMillis(config.getNodeLifetimeSeconds());
        connectionManager.getShardedNodeDirectoryClient(nodeKey).set(nodeKey, localAddress.toString(), expire).join();
        String result = (String) connectionManager.getShardedNodeDirectoryClient(nodeKey).get(nodeKey).join();
        Assert.assertEquals(localAddress.toString(), result);
    }

    @Test
    @Category(IntegrationTest.class)
    public void entryTest()
    {
        NodeAddress localAddress = new NodeAddressImpl(UUID.randomUUID());
        final String nodeKey = RedisKeyGenerator.nodeKey(clusterName, localAddress.toString());
        connectionManager.getShardedNodeDirectoryClient(nodeKey).set(nodeKey, localAddress.toString(), TimeUnit.SECONDS.toMillis(config.getNodeLifetimeSeconds())).join();

        String s = "this is some string";
        ByteBuffer b = codec.encodeKey(s);
        Assert.assertNotNull(b);
        String r = (String) codec.decodeKey(b);
        Assert.assertEquals(s, r);


        connectionManager.getShardedActorDirectoryClient(nodeKey).set(nodeKey, localAddress.toString(), TimeUnit.SECONDS.toMillis(config.getNodeLifetimeSeconds())).join();
    }

    @Test
    @Category(IntegrationTest.class)
    public void putIfAbsentTest()
    {
        RedisShardedMap map = new RedisShardedMap("test.map", connectionManager.getActorDirectoryClients(), 10);
        String key = UUID.randomUUID().toString();
        String value = "value";
        Assert.assertFalse(map.containsKey(key));
        Object o = map.putIfAbsent(key, value);
        Assert.assertNull(o);

        o = map.putIfAbsent(key, "wrong");
        Assert.assertEquals(value, o);
    }
}

