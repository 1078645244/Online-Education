package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

	@Resource
	private ShopServiceImpl shopService;

	@Resource
	private CacheClient cacheClient;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Test
	public void TestSaveShop() throws InterruptedException {
		Shop shop = shopService.getById(1L);

		cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
	}


	// @Resource
	// private StringRedisTemplate stringRedisTemplate;
	//
	// private static final DefaultRedisScript<Long> SECKILL_SCRIPT1 = new DefaultRedisScript<>();
	//
	// static {
	// 	SECKILL_SCRIPT1.setLocation(new ClassPathResource("init.lua"));
	// 	SECKILL_SCRIPT1.setResultType(Long.class);
	// }
	//
	// @Test
	// public void TestInitLua() {
	// 	Long result = stringRedisTemplate.execute(
	// 			SECKILL_SCRIPT1,
	// 			Collections.emptyList(),
	// 			new Object[0]
	// 	);
	// 	System.out.println(result + "*****************");
	// 	// 2.判断结果为0
	// 	int r = result.intValue();// Long转型为int
	// 	if (r != 0) {
	// 		// 2.1.不为0，代表没有购买资格
	// 		System.out.println("消费者组初始化失败");
	// 	}
	// }

	@Test
	void loadShopData() {
		// 1.查询店铺信息
		List<Shop> list = shopService.list();
		// 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
		Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
		// 3.分批完成写入Redis
		for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
			// 3.1.获取类型id
			Long typeId = entry.getKey();
			String key = SHOP_GEO_KEY + typeId;
			// 3.2.获取同类型的店铺的集合
			List<Shop> value = entry.getValue();
			List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
			// 3.3.写入redis GEOADD key 经度 纬度 member
			for (Shop shop : value) {
				// stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
				locations.add(new RedisGeoCommands.GeoLocation<>(
						shop.getId().toString(),
						new Point(shop.getX(), shop.getY())
				));
			}
			stringRedisTemplate.opsForGeo().add(key, locations);
		}
	}

	@Test
	void testHyperLogLog() {
		String[] values = new String[1000];
		int j = 0;
		for (int i = 0; i < 1000000; i++) {
			j = i % 1000;
			values[j] = "user_" + i;
			if(j == 999){
				// 发送到Redis
				stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
			}
		}
		// 统计数量
		Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
		System.out.println("count = " + count);
	}


}
