package com.jifeng.assessment.employee;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeeMapper extends BaseMapper<Employee> {

    @Select("SELECT * FROM employee WHERE employee_id = #{employeeId}")
    Employee selectByIdIgnoreDeleted(@Param("employeeId") String employeeId);

    @Delete("DELETE FROM employee WHERE employee_id = #{employeeId}")
    int physicalDeleteById(@Param("employeeId") String employeeId);
}
