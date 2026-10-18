package run.aihyb.pluginaihyb;

import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AdminController.class, PublicController.class, WebController.class})
public class PackageErrorHandler {
    @ExceptionHandler(PackageProblem.class)
    public ResponseEntity<PackageViews.Problem> invalid(PackageProblem problem) {
        return ResponseEntity.status(problem.status()).header("Cache-Control", "private, no-store")
            .body(new PackageViews.Problem(problem.getMessage(), problem.errors()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<PackageViews.Problem> conflict() {
        return ResponseEntity.status(409).header("Cache-Control", "private, no-store")
            .body(new PackageViews.Problem("版本冲突", List.of("资源已更新，请重新取得草稿版本")));
    }
}
