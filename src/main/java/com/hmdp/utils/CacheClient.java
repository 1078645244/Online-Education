package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author 林叶镔
 * @since 2023/8/15
 */

@Slf4j
@Component
public class CacheClient {

	private final StringRedisTemplate stringRedisTemplate;

	public CacheClient(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	public void set(String key, Object value, Long time, TimeUnit unit) {
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
	}

	public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
		//设置逻辑过期
		RedisData redisData = new RedisData();
		redisData.setData(value);
		redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
		//写入Redis
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
	}

	public <T, ID> T queryWithPassThrough(
			String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit unit) {
		String key = keyPrefix + id;
		// 1、从redis中查询商铺缓存
		String json = stringRedisTemplate.opsForValue().get(key);
		// 2、判断是否存在
		if (StrUtil.isNotBlank(json)) {
			// 存在,直接返回
			return JSONUtil.toBean(json, type);
		}
		//判断命中的值是否是空值
		if (json != null) {
			//返回一个错误信息
			return null;
		}
		// 4 不存在，根据id查询数据库
		T t = dbFallback.apply(id);
		// 5.不存在，返回错误
		if (t == null) {
			//将空值写入redis
			stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
			//返回错误信息
			return null;
		}
		//6.写入redis
		this.set(key, t, time, unit);
		return t;
	}

	private boolean tryLock(String key) {
		log.debug("tryLock***************************************");
		Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
		return BooleanUtil.isTrue(flag);
	}

	private void unlock(String key) {
		log.debug("unlock***************************************");
		stringRedisTemplate.delete(key);
	}

	public <T, ID> T queryWithMutex(
			String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallBack, Long time, TimeUnit unit) {
		String key = keyPrefix + id;
		// 1、从redis中查询商铺缓存
		String json = stringRedisTemplate.opsForValue().get(key);
		// 2、判断是否存在
		if (StrUtil.isNotBlank(json)) {
			log.debug("3***************************************");
			// 存在,直接返回
			return JSONUtil.toBean(json, type);
		}
		//判断命中的值是否是空值
		if (json != null) {
			log.debug("4***************************************");
			//返回一个错误信息
			return null;
		}
		// 4.实现缓存重构
		//4.1 获取互斥锁
		String lockKey = "lock:shop:" + id;
		T t = null;
		try {
			boolean isLock = tryLock(lockKey);
			// 4.2 判断否获取成功
			if (!isLock) {
				//4.3 失败，则休眠重试
				Thread.sleep(50);
				return queryWithMutex(keyPrefix, id, type, dbFallBack, time, unit);
			}
			// 4.4 成功，根据id查询数据库
			log.debug("5***************************************");
			t = dbFallBack.apply(id);
			// 模拟重建的延时
			// Thread.sleep(200);
			// 5.不存在，返回错误
			if (t == null) {
				//将空值写入redis
				this.set(key, "", time, unit);
				//返回错误信息
				log.debug("6***************************************");
				return null;
			}
			//6.写入redis
			log.debug("7***************************************");
			this.set(key, JSONUtil.toJsonStr(t), time, unit);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			//7.释放互斥锁
			unlock(lockKey);
		}
		log.debug("8***************************************");
		return t;
	}

	private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	public <T, ID> T queryWithLogicalExpire(
			String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallBack, Long time, TimeUnit unit) {
		String key = keyPrefix + id;
		// 1.从redis查询商铺缓存
		String json = stringRedisTemplate.opsForValue().get(key);
		// 2.判断是否存在
		if (StrUtil.isBlank(json)) {
			// 3.不存在，直接返回
			return null;
		}
		// 4.命中，需要先把json反序列化为对象
		RedisData redisData = JSONUtil.toBean(json, RedisData.class);
		T t = JSONUtil.toBean((JSONObject)redisData.getData(), type);
		LocalDateTime expireTime = redisData.getExpireTime();
		//5.判断是否过期
		if (expireTime.isAfter(LocalDateTime.now())) {
			//5.1.未过期，直接返回店铺信息
			return t;
		}
		// 5.2.已过期，需要缓存重建
		// 6.缓存重建
		// 6.1.获取互斥锁
		String lockKey = LOCK_SHOP_KEY + id;
		boolean isLock = tryLock(lockKey);
		// 6.2.判断是否获取锁成功
		if (isLock) {
			// 6.3.成功，开启独立线程，实现缓存重建
			CACHE_REBUILD_EXECUTOR.submit(() -> {
				// 重建缓存
				try {
					T t1 = dbFallBack.apply(id);
					this.setWithLogicalExpire(key, t1, time, unit);
				} catch (Exception e) {
					throw new RuntimeException(e);
				} finally {
					// 释放锁
					unlock(lockKey);
				}
			});
		}
		//6.4.返回过期的商铺信息return shop;
		return t;
	}
}
