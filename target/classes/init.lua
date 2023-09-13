redis.call('XGROUP', 'CREATE', 'stream.orders', 'g1', '0', 'MKSTREAM')
return 0