package yy.code.io.cosocket.status;

/**
 * Created by ${good-yy} on 2018/9/8.
 */
public final class BitIntStatusUtils {
    //删除其中的一个状态,添加另外一个状态
    public static int convertStatus(int beChange, int before, int after) {
        return (beChange & (~before)) & after;
    }

    //去除某一个状态
    public static int removeStatus(int beChange, int remove) {
        return beChange & (~remove);
    }

    //增加某一个状态
    public static int addStatus(int beChange, int add) {
        return beChange & add;
    }

    public static boolean isInStatus(int stat, int hope) {
        return (stat & hope) != 0;
    }
}
