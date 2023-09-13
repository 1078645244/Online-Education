package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

	@Autowired
	StringRedisTemplate stringRedisTemplate;

	@Resource
	private IShopTypeService typeService;

	@Override
	public Result queryList() {
		// 1.从redis查询商铺缓存
		String shopTypeList = stringRedisTemplate.opsForValue().get("cache:shop:type");
		// 2.判断是否存在
		if (StrUtil.isNotBlank(shopTypeList)){
			//  3.存在，直接返回
			List<ShopType> typeList = JSONUtil.toList(shopTypeList, ShopType.class);
			return Result.ok(typeList);
		}
		// 4.不存在，在mysql中查询数据库
		List<ShopType> typeList = typeService
		        .query().orderByAsc("sort").list();
		// 5.不存在，返回错误
		if (typeList == null) {
			return Result.fail("列表不存在!");
		}
		// 6.存在，写入redis
		stringRedisTemplate.opsForValue().set("cache:shop:type", JSONUtil.toJsonStr(typeList));
		//7.返回
		return Result.ok(typeList);
	}
}
