import uvicorn

from comet.app import app
from comet.config import settings


def run() -> None:
    uvicorn.run(
        app,
        host=settings.fastapi_host,
        port=settings.fastapi_port,
        log_level=settings.log_level.lower(),
        access_log=False,
    )


if __name__ == "__main__":
    run()
