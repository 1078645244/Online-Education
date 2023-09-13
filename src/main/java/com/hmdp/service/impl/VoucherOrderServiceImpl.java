package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

	@Resource
	private ISeckillVoucherService seckillVoucherService;

	@Resource
	private RedisIdWorker redisIdWorker;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	@Resource
	private RedissonClient redissonClient;

	private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
	private static final DefaultRedisScript<Long> SECKILL_SCRIPT1;

	static {
		SECKILL_SCRIPT = new DefaultRedisScript<>();
		SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
		SECKILL_SCRIPT.setResultType(Long.class);

		SECKILL_SCRIPT1 = new DefaultRedisScript<>();
		SECKILL_SCRIPT1.setLocation(new ClassPathResource("init.lua"));
		SECKILL_SCRIPT1.setResultType(Long.class);
	}

	// private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
	private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

	@PostConstruct
	private void init() {
		// TODO 不知道为什么和在服务端程序启动前手动在redis里输入XGROUP CREATE stream.orders g1 0 MKSTREAM效果不同
		/*// 初始化，创建消费者组
		Long result = stringRedisTemplate.execute(
				SECKILL_SCRIPT1,
				Collections.emptyList(),
				new Object[0]
		);
		log.debug("---------------------------------------");
		// 2.判断结果为0
		int r = result.intValue();// Long转型为int
		if (r != 0) {
			// 2.1.不为0，代表没有购买资格
			log.debug("消费者组初始化失败");
		}*/
		SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
	}

	private class VoucherOrderHandler implements Runnable {
		private String queueName = "stream.orders";
		@Override
		public void run() {
			while (true) {
				try {
					// 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
					List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
							Consumer.from("g1", "c1"),
							StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
							StreamOffset.create(queueName, ReadOffset.lastConsumed())
					);
					// 2.判断订单信息是否为空
					if (list == null || list.isEmpty()) {
						// 如果为null，说明没有消息，继续下一次循环
						continue;
					}
					// 解析数据
					MapRecord<String, Object, Object> record = list.get(0);
					Map<Object, Object> value = record.getValue();
					VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
					log.debug(voucherOrder + "1111111111111111111");
					// 3.创建订单
					createVoucherOrder(voucherOrder);
					// 4.确认消息 XACK
					stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
				} catch (Exception e) {
					log.error("处理订单异常", e);
					handlePendingList();
				}
			}
			/*while (true) {
				try {
					// 1.获取队列中的订单信息
					VoucherOrder voucherOrder = orderTasks.take();
					// 2.创建订单
					handleVoucherOrder(voucherOrder);
				} catch (Exception e) {
					log.error("处理订单异常", e);
				}
			}*/
		}

		private void handlePendingList() {
			while (true) {
				try {
					// 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
					List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
							Consumer.from("g1", "c1"),
							StreamReadOptions.empty().count(1),
							StreamOffset.create(queueName, ReadOffset.from("0"))
					);
					// 2.判断订单信息是否为空
					if (list == null || list.isEmpty()) {
						// 如果获取失败，说明pending-list没有异常消息，结束循环
						break;
					}
					// 解析数据
					MapRecord<String, Object, Object> record = list.get(0);
					Map<Object, Object> value = record.getValue();
					VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
					// 3.创建订单
					createVoucherOrder(voucherOrder);
					// 4.确认消息 XACK
					stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
				} catch (Exception e) {
					log.error("处理pending-list异常", e);
					try {
						Thread.sleep(20);
					} catch (InterruptedException ex) {
						ex.printStackTrace();
					}
				}
			}
		}

		// private void handleVoucherOrder(VoucherOrder voucherOrder) {
		// // 1.获取用户
		// Long userId = voucherOrder.getUserId();
		// // 2.创建锁对象
		// RLock lock = redissonClient.getLock("lock:order:" + userId);
		// // 3.获取锁
		// boolean isLock = lock.tryLock();
		// //4.判断是否获取锁成功
		// if (!isLock) {
		// 	//获取锁失败，返回错误或重试
		// 	log.error("不允许重复下单");
		// 	return;
		// }
		// try {
		// 	save(voucherOrder);
		// 	// 6.扣减库存
		// 	boolean success = seckillVoucherService.update()
		// 			.setSql("stock = stock -1")
		// 			.eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
		// 			.update();
		// 	if (!success) {
		// 		// 扣减失败
		// 		log.error("库存不足！");
		// 	}
		// } finally {
		// 	// 释放锁
		// 	lock.unlock();
		// }
		// }
	}

	// private IVoucherOrderService proxy;

	@Override
	public Result seckillVoucher(Long voucherId) {
		Long userId = UserHolder.getUser().getId();
		long orderId = redisIdWorker.nextId("order");
		// 1.执行lua脚本
		Long result = stringRedisTemplate.execute(
				SECKILL_SCRIPT,
				Collections.emptyList(),
				voucherId.toString(), userId.toString(), String.valueOf(orderId)
		);
		// 2.判断结果为0
		int r = result.intValue();// Long转型为int
		if (r != 0) {
			// 2.1.不为0，代表没有购买资格
			return Result.fail(r == 1 ? "库存不足" : "不可重复下单!");
		}
		// 2.2.为0，有购买资格，把下单信息保存到阻塞队列
		VoucherOrder voucherOrder = new VoucherOrder();
		// 2.3.订单id
		voucherOrder.setId(orderId);
		// 2.4.用户id
		voucherOrder.setUserId(userId);
		// 2.5.代金券id
		voucherOrder.setVoucherId(voucherId);
		/*// 2.6.放入阻塞队列
		orderTasks.add(voucherOrder);*/
		/*这里没有按照视频中的方法来
		// 3.获取代理对象
		proxy = (IVoucherOrderService) AopContext.currentProxy();
		proxy.CreateVoucherOrder(voucherId);*/
		// 4.返回订单id
		return Result.ok(orderId);
	}

	private void createVoucherOrder(VoucherOrder voucherOrder) {
		Long userId = voucherOrder.getUserId();
		Long voucherId = voucherOrder.getVoucherId();
		// 创建锁对象
		RLock redisLock = redissonClient.getLock("lock:order:" + userId);
		// 尝试获取锁
		boolean isLock = redisLock.tryLock();
		// 判断
		if (!isLock) {
			// 获取锁失败，直接返回失败或者重试
			log.error("不允许重复下单！");
			return;
		}

		try {
			// 5.1.查询订单
			int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
			// 5.2.判断是否存在
			if (count > 0) {
				// 用户已经购买过了
				log.error("不允许重复下单！");
				return;
			}

			// 6.扣减库存
			boolean success = seckillVoucherService.update()
					.setSql("stock = stock - 1") // set stock = stock - 1
					.eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
					.update();
			if (!success) {
				// 扣减失败
				log.error("库存不足！");
				return;
			}

			// 7.创建订单
			save(voucherOrder);
		} finally {
			// 释放锁
			redisLock.unlock();
		}
	}

	/*@Override
	public Result seckillVoucher(Long voucherId) {
		//1.查询优惠券
		SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
		//2.判断秒杀是否开始
		if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
			//尚未开始
			return Result.fail("秒杀尚未开始!");
		}
		//3.判断秒杀是否已经结束
		if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
			//尚未开始
			return Result.fail("秒杀已经结束!");
		}
		//4.判断库存是否充足
		if (voucher.getStock() < 1) {
			//库存不足
			return Result.fail("库存不足!");
		}

		Long userId = UserHolder.getUser().getId();
		// 创建锁对象
		SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
		// 获取锁对象
		boolean isLock = simpleRedisLock.tryLock(1200);
		// 加锁失败
		if(!isLock) {
			return Result.fail("不可重复下单！");
		}
		//synchronized (userId.toString().intern()) {
		try {
			// 获取代理对象（原因是@Transcational实现事务的是基于Spring的aop代理机制，
			// 因此必须用代理对象才能生效,用this的话是当前对象(也称目标对象)，无法生效）
			IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
			//return this.createVoucherOrder(voucherId);
			return proxy.createVoucherOrder(voucherId);
		} finally {
			simpleRedisLock.unlock();
		}
	}*/

	/*@Transactional
	public Result createVoucherOrder(Long voucherId) {
		// 5.一人一单
		Long userId = UserHolder.getUser().getId();
		// 5.1.查询订单
		int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
		// 5.2.判断是否存在
		if (count > 0) {
			// 用户已经购买过了
			return Result.fail("用户已经购买过一次!");
		}

		// 6.扣减库存
		boolean success = seckillVoucherService.update()
				.setSql("stock = stock -1")
				.eq("voucher_id", voucherId).gt("stock", 0)
				.update();
		if (!success) {
			// 扣减失败
			return Result.fail("库存不足！");
		}

		// 7.创建订单
		VoucherOrder voucherOrder = new VoucherOrder();
		// 7.1.订单id
		long orderId = redisIdWorker.nextId("order");
		voucherOrder.setId(orderId);
		// 7.2.用户id
		voucherOrder.setUserId(userId);
		// 7.3.代金券id
		voucherOrder.setVoucherId(voucherId);
		save(voucherOrder);

		return Result.ok(orderId);
	}*/
}
