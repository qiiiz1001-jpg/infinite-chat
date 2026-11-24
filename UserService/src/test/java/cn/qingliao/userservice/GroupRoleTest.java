package cn.qingliao.userservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.qingliao.userservice.service.impl.GroupServiceImpl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GroupRoleTest {
    @ParameterizedTest
    @CsvSource({
        "0,0,false",
        "0,1,true",
        "0,2,true",
        "1,0,false",
        "1,1,false",
        "1,2,true",
        "2,0,false",
        "2,1,false",
        "2,2,false"
    })
    void permissionMatrixProtectsOwnerAndPeers(int operator, int target, boolean allowed) {
        assertEquals(allowed, GroupServiceImpl.canKick(operator, target));
    }
}
