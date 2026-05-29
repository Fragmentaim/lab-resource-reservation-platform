package com.fragment.labbooking;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.fragment.labbooking.mapper")
@SpringBootApplication
@EnableScheduling
public class LabResourceBookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabResourceBookingApplication.class, args);
    }

}
