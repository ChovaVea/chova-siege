package com.oxford.cluster;

/**
 * SnowFlake的优点是，整体上按照时间自增排序，并且整个分布式系统内不会产生ID碰撞(由数据中心ID和机器ID作区分)，效率较高
 * SnowFlake每秒能够产生26万ID左右
 *
 * SnowFlake的结构如下(每部分用-分开):
 *      0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000
 * - 1位标识，由于long基本类型在Java中是带符号的，最高位是符号位，正数是0，负数是1，所以id一般是正数，最高位是0
 * - 41位时间截(毫秒级)，注意，41位时间截不是存储当前时间的时间截，而是存储时间截的差值（当前时间截 - 开始时间截)
 *   得到的值），这里的的开始时间截，默认是1980-01-01(315504000000L)，一般是我们的id生成器开始使用的时间，由我们程序来指定的（如下下面程序IdWorker类的startTime属性）。
 *   41位的时间截，可以使用69年，年T = (1L << 41) / (1000L * 60 * 60 * 24 * 365) = 69
 * - 10位的数据机器位，可以部署在1024个节点，包括5位datacenterId和5位workerId
 * - 12位序列，毫秒内的计数，12位的计数顺序号支持每个节点每毫秒(同一机器，同一时间截)产生4096个ID序号
 * 加起来刚好64位，为一个Long型
 *
 * @author Chova
 * @date 2020/07/09
 */
public class SnowflakeIdWorker {

//=====================================属性========================================

    /** 开始时间戳 2020-01-01 */
    private final long twepoch = 1577808000000L;

    /** 机器Id所占位数 */
    private final long workerIdBits = 4L;

    /** 数据标识Id所占的位数 */
    private final long dataCenterIdBits = 4L;

    /** 支持的最大机器id,结果为 31 */
    private final long maxWorkerId = ~(-1L << workerIdBits);

    /** 支持的最大数据标识Id,结果为 31 */
    private final long maxDataCenterId = ~(-1L << dataCenterIdBits);

    /** 序列在Id中所占的位数 */
    private  final long sequenceBits = 12L;

    /** 机器Id向左移序列的位数 12位 */
    private final long workerIdShift = sequenceBits;

    /**
     * 数据中心Id向左移序列位数和机器Id位数所占位数之和 12+4+1 17位
     * 其中1来自 private final long maxWorkerId = ~(-1L << workerIdBits);
     */
    private final long datacenterIdShift = sequenceBits + workerIdBits;

    /**
     * 时间戳向左移序列位数和机器Id位数和数据标识Id位数所占位数之和 12+4+1+4+1 22位
     * 其中的1分别来自:
     *      private final long maxWorkerId = ~(-1L << workerIdBits);
     *      private final long maxDatacenterId = ~(-1L << datacenterIdBits);
     */
    private final long timestampLeftShift = sequenceBits + workerIdBits + dataCenterIdBits;

    /** 机器Id向左移序列的位数 12位 */
    private final long sequenceMask = ~(-1 << sequenceBits);

    /** 工作机器Id, 10位最大为 1023 */
    private long workerId;

    /** 数据中心Id, 5位最大为 31 */
    private long dataCenterId;

    /** 毫秒内序列,12位最大为 4095 */
    private long sequence = 0L;

    /** 上次生成Id的时间戳 */
    private long lastTimestamp = -1L;
//=====================================调用方法==========================================

    /**
     * SnowflakeIdWorker生成ID
     * 默认生成方法的工作机器ID为0，数据中心ID为0
     *
     * @return long 数据ID
     */
    public static long nextId() {
        SnowflakeIdWorker idWorker = new SnowflakeIdWorker(0, 0);
        return idWorker.next();
    }

    /**
     * 设置服务器数目来生成SnowflakeIdWorker生成ID
     *
     * @param workerId 工作机器ID
     * @return long 数据ID
     */
    public static long nextId(long workerId) {
        SnowflakeIdWorker idWorker = new SnowflakeIdWorker(workerId, 0);
        return idWorker.next();
    }

    /**
     * 设置服务器数目来生成SnowflakeIdWorker生成ID
     *
     * @param workerId     工作机器ID
     * @param dataCenterId 数据中心Id
     * @return long 数据ID
     */
    public static long nextId(long workerId, long dataCenterId) {
        SnowflakeIdWorker idWorker = new SnowflakeIdWorker(workerId, dataCenterId);
        return idWorker.next();
    }

//=====================================构造方法==========================================

    /**
     * SnowflakeIdWorker构造方法
     *
     * @param workerId 工作机器Id，10位最大为 1023
     */
    private SnowflakeIdWorker(long workerId) {
        this(workerId,0);
    }

    /**
     * SnowflakeIdWorker构造函数
     *
     * @param workerId 工作机器Id，10位最大为 1023
     * @param dataCenterId 数据中心Id，5位最大为 31
     */
    private SnowflakeIdWorker(long workerId, long dataCenterId) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException(String.format("worker Id can't be greater than %d or less than 0", maxWorkerId));
        }
        if (dataCenterId > maxDataCenterId || dataCenterId < 0) {
            throw new IllegalArgumentException(String.format("dataCenterId Id can't be greater than %d or less than 0", maxDataCenterId));
        }
        this.workerId = workerId;
        this.dataCenterId = dataCenterId;
    }

//====================================ID生成方法==========================================

    /**
     * 获取下一个ID
     * 该方法是线程安全方法
     *
     * @return long SnowflakeId
     */
    public synchronized long next() {
        long timestamp = timeGen();

        /*
         * 如果当前时间小于上一次ID生成的时间戳，说明系统时钟回退过这个时候应当抛出异常
         */
        if  (timestamp < lastTimestamp) {
            throw new RuntimeException(String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", lastTimestamp));
        }

        /*
         * 如果是同一时间生成的，则进行毫秒内序列
         */
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            // 毫秒内序列溢出
            if (sequence == 0) {
                // 阻塞到下一个毫秒,获得新的时间戳
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 时间戳改变，毫秒内序列重置
            sequence = 0L;
        }

        // 上次生成ID的时间截
        lastTimestamp = timestamp;

        // 移位并通过或运算拼到一起组成64位的ID
        return ((timestamp - twepoch) << timestampLeftShift)
                | (dataCenterId) << datacenterIdShift
                | (workerId) << workerIdShift
                | sequence;
    }

    /**
     * 阻塞到下一个毫秒，直到获得新的时间戳
     *
     * @param lastTimestamp 上次生成ID的时间截
     * @return long 当前时间戳
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 返回以毫秒为单位的当前时间
     *
     * @return 当前时间，以毫秒为单位
     */
    private long timeGen() {
        return System.currentTimeMillis();
    }

}
