"""Use the same pure converter to validate downloads and generate offline drafts."""
from .common import require


def convert(adapter, raw, *, retrieved_at, generated_at, supplements=None, source_times=None):
    if adapter == "modeldial":
        from .modeldial import convert as convert_modeldial
        require(not supplements, "ModelDial does not use supplements")
        return convert_modeldial(raw, retrieved_at=retrieved_at, generated_at=generated_at)
    require(adapter == "open-models", f"Unknown adapter: {adapter}")
    from .open_models import convert as convert_open_models
    return convert_open_models(raw, retrieved_at=retrieved_at, generated_at=generated_at,
                               supplements=supplements, source_times=source_times)
