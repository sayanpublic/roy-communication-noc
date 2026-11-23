package in.roycommunication.noc.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/replays", produces = MediaType.APPLICATION_JSON_VALUE)
class ReplayController {
    private final List<ReplayView> views = SyntheticReplays.load();

    @GetMapping
    List<String> viewIds() {
        return views.stream().map(ReplayView::viewId).toList();
    }

    @GetMapping("/{viewId}")
    ResponseEntity<ReplayView> view(@PathVariable("viewId") String viewId) {
        return ResponseEntity.of(views.stream().filter(view -> view.viewId().equals(viewId)).findFirst());
    }
}
