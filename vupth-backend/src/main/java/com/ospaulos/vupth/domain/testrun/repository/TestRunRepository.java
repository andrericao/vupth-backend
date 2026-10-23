package com.ospaulos.vupth.domain.testrun.repository;

import com.ospaulos.vupth.domain.testrun.model.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {
}
